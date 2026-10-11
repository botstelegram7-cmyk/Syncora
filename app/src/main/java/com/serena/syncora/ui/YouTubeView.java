package com.serena.syncora.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.SessionState;
import com.serena.syncora.Source;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.audio.ArtLoader;
import com.serena.syncora.youtube.DirectLink;
import com.serena.syncora.youtube.DirectLinkActions;
import com.serena.syncora.youtube.DownloadQueue;
import com.serena.syncora.youtube.DownloadStore;
import com.serena.syncora.youtube.TypeTube;
import com.serena.syncora.youtube.YouTubeActions;
import com.serena.syncora.youtube.YouTubeClient;
import com.serena.syncora.youtube.YouTubeDownloader;
import com.serena.syncora.youtube.YouTubeFormat;
import com.serena.syncora.youtube.YouTubeIds;
import com.serena.syncora.youtube.YouTubeLookupLimiter;
import com.serena.syncora.youtube.YouTubePlaylist;
import com.serena.syncora.youtube.YouTubeVideo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTube: paste a link, then play the video's audio in sync (on the phone and every speaker), or save the video,
 * an audio stream, the thumbnail, lyrics or subtitles. Downloads run in the background and are listed below.
 * Direct media links (like telegram-file-to-links {@code /stream?id=} links) play from their stream link and
 * save through their download link.
 */
final class YouTubeView extends SourceView {
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final YouTubeLookupLimiter LOOKUP_LIMITER = new YouTubeLookupLimiter(2000);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DownloadQueue queue;
    private final EditText input;
    private final TextView findButton;
    private final TextView status;
    private final LinearLayout details;
    private boolean lookupBusy;
    private YouTubePlaylist playlist;
    private final LinearLayout jobsColumn;
    private final DownloadQueue.Listener queueListener = new DownloadQueue.Listener() {
        @Override
        public void onQueueChanged() {
            renderJobs();
        }
    };
    private final Runnable findCooldownTick = new Runnable() {
        @Override
        public void run() {
            updateFindButton();
        }
    };
    private YouTubeVideo video;
    private DirectLink direct;
    private int fetchId;

    YouTubeView(Context context, UiHost host) {
        super(context, host);
        queue = DownloadQueue.get(context);

        ScrollView scroll = new ScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(120));
        scroll.addView(column, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));

        // --- link box
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(context);
        input.setHint("Paste a YouTube or media link");
        input.setHintTextColor(Theme.subtle);
        input.setTextColor(Theme.text);
        input.setTextSize(15);
        input.setTypeface(Ui.REGULAR);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        Ui.setBackground(input, Ui.stroke(Theme.surface2, Theme.line, 1, 14));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    fetch();
                    return true;
                }
                return false;
            }
        });
        row.addView(input, Ui.linW(1f, Ui.WRAP, 0, 0, Ui.dp(10), 0));
        findButton = Ui.primaryButton(context, "Find");
        findButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch();
            }
        });
        Ui.pressable(findButton);
        row.addView(findButton, Ui.lin(Ui.WRAP, Ui.WRAP));
        column.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(6), 0, Ui.dp(10)));

        status = Ui.text(context, "", 13, Theme.muted, Ui.REGULAR);
        column.addView(status, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(10)));

        // --- the loaded video: card, play, downloads
        details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        column.addView(details, Ui.lin(Ui.MATCH, Ui.WRAP));

        // --- queue
        column.addView(sectionLabel(context, "Downloads"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(22), 0, Ui.dp(8)));
        jobsColumn = new LinearLayout(context);
        jobsColumn.setOrientation(LinearLayout.VERTICAL);
        column.addView(jobsColumn, Ui.lin(Ui.MATCH, Ui.WRAP));

        TextView note = Ui.text(context,
                "Only save content you have the right to keep: your own uploads, Creative Commons videos, or "
                        + "content YouTube itself lets you download. YouTube's terms still apply. Files are saved to "
                        + DownloadStore.locationHint() + ".",
                12, Theme.subtle, Ui.REGULAR);
        column.addView(note, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(22), 0, 0));

        queue.addListener(queueListener);
        renderJobs();
        renderDetails();
    }

    @Override
    String subtitle(SessionState state) {
        if (state.source == Source.YOUTUBE && !state.title.isEmpty()) return state.title;
        return "Play a video's audio in sync, or save it";
    }

    @Override
    public void onState(SessionState state) {
        if (video == null && state.source == Source.YOUTUBE) {
            SyncoraService service = host.service();
            YouTubeVideo current = service == null ? null : service.currentYouTube();
            if (current != null) show(current);
        }
    }

    @Override
    void onRelease() {
        queue.removeListener(queueListener);
        main.removeCallbacks(findCooldownTick);
    }

    // ================================================================= fetching

    private void fetch() {
        String text = DirectLink.normalize(input.getText().toString());
        String playlistId = YouTubeIds.playlistId(text);
        if (playlistId != null) {
            fetchPlaylist(playlistId);
            return;
        }
        String id = YouTubeIds.parse(text);
        if (id != null) {
            fetchYouTube(id, false, "");
            return;
        }
        DirectLink link = DirectLink.parse(text);
        if (link != null) {
            fetchDirect(link);
            return;
        }
        status.setText("Paste a YouTube video or playlist link, video ID, or direct media link.");
    }

    private boolean beginLookup() {
        long wait = LOOKUP_LIMITER.tryAcquire();
        if (wait < 0) {
            status.setText("A YouTube request is already running. Please wait for it to finish.");
            return false;
        }
        if (wait > 0) {
            status.setText("Please wait " + Math.max(1, (wait + 999) / 1000) + " seconds before another lookup.");
            updateFindButton();
            return false;
        }
        lookupBusy = true;
        updateFindButton();
        return true;
    }

    private void finishLookup() {
        lookupBusy = false;
        updateFindButton();
    }

    private void updateFindButton() {
        if (lookupBusy) {
            findButton.setText("Loading…");
            findButton.setEnabled(false);
            findButton.setAlpha(0.65f);
            main.removeCallbacks(findCooldownTick);
            return;
        }
        long wait = LOOKUP_LIMITER.remainingMillis();
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            findButton.setText("Wait " + seconds + "s");
            findButton.setEnabled(false);
            findButton.setAlpha(0.65f);
            main.removeCallbacks(findCooldownTick);
            main.postDelayed(findCooldownTick, Math.min(wait, 1000));
            return;
        }
        main.removeCallbacks(findCooldownTick);
        findButton.setText("Find");
        findButton.setEnabled(true);
        findButton.setAlpha(1f);
    }

    private void fetchPlaylist(final String id) {
        if (!beginLookup()) return;
        final int request = ++fetchId;
        playlist = null;
        video = null;
        direct = null;
        renderDetails();
        status.setText("Loading playlist…");
        IO.execute(new Runnable() {
            @Override
            public void run() {
                YouTubePlaylist result = null;
                String reason = "";
                try {
                    result = YouTubeClient.fetchPlaylist(id);
                } catch (Exception e) {
                    reason = e.getMessage() == null ? "network error" : e.getMessage();
                } finally {
                    LOOKUP_LIMITER.release();
                }
                final YouTubePlaylist loaded = result;
                final String error = reason;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishLookup();
                        if (request != fetchId) return;
                        if (loaded != null) {
                            playlist = loaded;
                            status.setText("Loaded " + loaded.entries.size() + " videos. Tap a song to play it.");
                            renderDetails();
                        } else {
                            status.setText("Couldn't read this playlist: " + error);
                        }
                    }
                });
            }
        });
    }

    /** Resolves a video through TypeTube first, then YouTube's own player API. */
    private void fetchYouTube(final String id, final boolean playWhenReady, final String selectedTitle) {
        if (!beginLookup()) return;
        final int request = ++fetchId;
        if (!playWhenReady) {
            playlist = null;
            video = null;
            direct = null;
            renderDetails();
        }
        status.setText(playWhenReady && !selectedTitle.isEmpty()
                ? "Preparing \"" + selectedTitle + "\"…" : "Reading the video…");
        IO.execute(new Runnable() {
            @Override
            public void run() {
                YouTubeVideo found = null;
                String why = "";
                boolean resolvedByTypeTube = false;
                try {
                    try {
                        found = TypeTube.fetch(id);
                        if (found != null && found.bestAudioForPlayback() == null) {
                            why = "TypeTube returned no audio stream";
                            found = null;
                        }
                        resolvedByTypeTube = found != null;
                    } catch (Exception e) {
                        why = e.getMessage() == null ? "network error" : e.getMessage();
                    }
                    if (found == null) {
                        try {
                            found = YouTubeClient.fetch(id);
                        } catch (Exception e) {
                            String also = e.getMessage() == null ? "network error" : e.getMessage();
                            why = why.isEmpty() ? also : why + " / YouTube: " + also;
                        }
                    } else {
                        final YouTubeVideo immediate = found;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (request != fetchId) return;
                                show(immediate);
                                status.setText("Checking for subtitles…");
                                if (playWhenReady) playAudio();
                            }
                        });
                        if (found.captions.isEmpty()) {
                            // TypeTube supplies media URLs but not captions. Fill that gap without delaying playback.
                            try {
                                List<YouTubeVideo.Caption> captions = YouTubeClient.fetchCaptions(id);
                                if (!captions.isEmpty()) found = found.withCaptions(captions);
                            } catch (Exception ignored) {
                                // Playback should still work when caption metadata is unavailable.
                            }
                        }
                    }
                } finally {
                    LOOKUP_LIMITER.release();
                }
                final YouTubeVideo result = found;
                final String reason = why;
                final boolean typeTubeResolved = resolvedByTypeTube;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishLookup();
                        if (request != fetchId) return;
                        if (result != null) {
                            if (!typeTubeResolved || video != result) show(result);
                            else status.setText("");
                            if (!typeTubeResolved && playWhenReady) playAudio();
                        } else {
                            status.setText("Couldn't read this video: " + reason);
                        }
                    }
                });
            }
        });
    }

    /** A direct media link: one stream link to play, one download link to save. */
    private void fetchDirect(final DirectLink link) {
        ++fetchId;
        List<YouTubeFormat> formats = new ArrayList<>();
        formats.add(new YouTubeFormat(0, YouTubeFormat.Kind.AUDIO_ONLY, "", "", link.streamUrl,
                0, 0, 0, "", 0, 2, 0));
        playlist = null;
        direct = link;
        video = new YouTubeVideo("d" + Integer.toHexString(link.streamUrl.hashCode()), link.title,
                link.host, 0, "", YouTubeDownloader.USER_AGENT, formats, new ArrayList<YouTubeVideo.Caption>());
        status.setText("");
        renderDetails();
    }

    private void show(YouTubeVideo loaded) {
        video = loaded;
        direct = null;
        status.setText(loaded.formats.isEmpty() ? "No downloadable streams were found for this video." : "");
        renderDetails();
    }

    // ================================================================= rendering

    private void renderDetails() {
        details.removeAllViews();
        Context c = getContext();
        if (playlist != null) renderPlaylist(c, playlist);
        final YouTubeVideo v = video;
        if (v == null) return;

        if (direct != null) {
            renderDirect(v, direct);
            return;
        }

        // card with the thumbnail
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        Ui.setBackground(card, Ui.stroke(Theme.surface, Theme.line, 1, 16));
        final ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.setBackground(thumb, Ui.rect(Theme.surface3, 10));
        card.addView(thumb, Ui.lin(Ui.dp(128), Ui.dp(72)));
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(12), 0, 0, 0);
        TextView title = Ui.text(c, v.title, 15, Theme.text, Ui.MEDIUM);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title, Ui.lin(Ui.MATCH, Ui.WRAP));
        info.addView(Ui.text(c, v.author + " \u00B7 " + Ui.formatTime(v.durationMs), 12, Theme.muted, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(4), 0, 0));
        card.addView(info, Ui.linW(1f, Ui.WRAP, 0, 0, 0, 0));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playAudio();
            }
        });
        Ui.pressable(card);
        details.addView(card, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(14)));

        IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = ArtLoader.loadFromUrl(v.thumbnailUrl, 320);
                if (bitmap == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (video == v) thumb.setImageBitmap(bitmap);
                    }
                });
            }
        });

        TextView play = Ui.primaryButton(c, "Play audio in sync");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playAudio();
            }
        });
        Ui.pressable(play);
        details.addView(play, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        details.addView(Ui.text(c, "Tap the thumbnail or button to play audio through this phone and the room speakers.",
                12, Theme.subtle, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(4)));

        // video with sound
        List<YouTubeFormat> videos = v.mp4VideoFormats();
        details.addView(sectionLabel(c, "Save video with sound"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(18), 0, Ui.dp(6)));
        if (videos.isEmpty()) {
            details.addView(Ui.text(c, "No MP4 video streams are available for this video.", 13, Theme.muted,
                    Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP));
        }
        for (final YouTubeFormat f : videos) {
            details.addView(itemRow(c, f.label() + " \u00B7 MP4", "Save", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    start("Video", v, YouTubeActions.video(getContext(), v, f));
                }
            }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        }

        // audio only
        List<YouTubeFormat> audio = v.audioFormats();
        details.addView(sectionLabel(c, "Save audio only"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(18), 0, Ui.dp(6)));
        if (audio.isEmpty()) {
            details.addView(Ui.text(c, "No audio streams are available for this video.", 13, Theme.muted, Ui.REGULAR),
                    Ui.lin(Ui.MATCH, Ui.WRAP));
        }
        for (final YouTubeFormat f : audio) {
            details.addView(itemRow(c, f.label() + " \u00B7 " + f.extension().toUpperCase(), "Save",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            start("Audio", v, YouTubeActions.audio(getContext(), v, f));
                        }
                    }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        }

        // thumbnail, lyrics, subtitles
        details.addView(sectionLabel(c, "Thumbnail, lyrics and subtitles"),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(18), 0, Ui.dp(6)));
        details.addView(itemRow(c, "Thumbnail \u00B7 JPEG", "Save", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                start("Thumbnail", v, YouTubeActions.thumbnail(getContext(), v));
            }
        }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        details.addView(itemRow(c, "Lyrics \u00B7 LRC (synced when found)", "Save", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                start("Lyrics", v, YouTubeActions.lyrics(getContext(), v));
            }
        }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        for (final YouTubeVideo.Caption cap : v.captions) {
            String name = cap.name + (cap.auto ? " (auto)" : "");
            details.addView(captionRow(c, name, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    start("Subtitles", v, YouTubeActions.captions(getContext(), v, cap, true));
                }
            }, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    start("Subtitles", v, YouTubeActions.captions(getContext(), v, cap, false));
                }
            }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        }
        if (v.captions.isEmpty()) {
            details.addView(Ui.text(c, "This video has no subtitle or caption tracks.", 13, Theme.muted, Ui.REGULAR),
                    Ui.lin(Ui.MATCH, Ui.WRAP));
        }
    }

    /** Public playlist rows stay visible while a selected song is being resolved or played. */
    private void renderPlaylist(Context c, final YouTubePlaylist loaded) {
        details.addView(sectionLabel(c, loaded.title), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        String count = loaded.entries.size() + (loaded.entries.size() == 1 ? " video" : " videos");
        details.addView(Ui.text(c, count + " · tap any song to play it", 12, Theme.subtle, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(10)));
        for (int i = 0; i < loaded.entries.size(); i++) {
            final YouTubePlaylist.Entry entry = loaded.entries.get(i);
            String label = entry.title;
            if (!entry.author.isEmpty()) label += " · " + entry.author;
            if (entry.durationMs > 0) label += " · " + Ui.formatTime(entry.durationMs);
            View.OnClickListener play = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    fetchYouTube(entry.videoId, true, entry.title);
                }
            };
            View row = itemRow(c, label, "Play", play);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(play);
            Ui.pressable(row);
            details.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        }
        if (loaded.hasMore) {
            details.addView(Ui.text(c, "Showing the first 200 videos to keep the list responsive.",
                    12, Theme.subtle, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(4), 0, Ui.dp(8)));
        }
        if (video != null) {
            details.addView(sectionLabel(c, "Selected video"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(18), 0, Ui.dp(8)));
        }
    }

    /** Compact card for a direct media link: play its stream link, save through its download link. */
    private void renderDirect(final YouTubeVideo v, final DirectLink link) {
        Context c = getContext();

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        Ui.setBackground(card, Ui.stroke(Theme.surface, Theme.line, 1, 16));
        card.addView(Ui.text(c, "Direct link \u00B7 " + link.host, 12, Theme.muted, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP));
        TextView title = Ui.text(c, link.title, 15, Theme.text, Ui.MEDIUM);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(4), 0, 0));
        details.addView(card, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(14)));

        TextView play = Ui.primaryButton(c, "Play audio in sync");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playAudio();
            }
        });
        Ui.pressable(play);
        details.addView(play, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        details.addView(Ui.text(c, "Streams from the link and plays the audio on this phone and any "
                + "speakers in the room.", 12, Theme.subtle, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(4)));

        details.addView(sectionLabel(c, "Save the file"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(14), 0, Ui.dp(6)));
        details.addView(itemRow(c, link.title + " \u00B7 from the download link", "Save",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        start("File", v, DirectLinkActions.save(getContext(), link));
                    }
                }), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(6)));
        details.addView(Ui.text(c, "Files go to " + DownloadStore.locationHint() + ".", 12, Theme.subtle,
                Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP));
    }

    private void renderJobs() {
        jobsColumn.removeAllViews();
        Context c = getContext();
        List<DownloadQueue.Job> jobs = queue.jobs();
        if (jobs.isEmpty()) {
            jobsColumn.addView(Ui.text(c, "Nothing downloading yet.", 13, Theme.subtle, Ui.REGULAR),
                    Ui.lin(Ui.MATCH, Ui.WRAP));
            return;
        }
        for (int i = jobs.size() - 1; i >= 0; i--) {
            jobsColumn.addView(jobRow(c, jobs.get(i)), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(8)));
        }
        TextView clear = Ui.secondaryButton(c, "Clear finished");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                queue.clearFinished();
            }
        });
        jobsColumn.addView(clear, Ui.lin(Ui.WRAP, Ui.WRAP, 0, Ui.dp(2), 0, 0));
    }

    private View jobRow(Context c, final DownloadQueue.Job job) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        Ui.setBackground(box, Ui.stroke(Theme.surface, Theme.line, 1, 14));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(c, job.kind + " \u00B7 " + job.title, 14, Theme.text, Ui.MEDIUM);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, Ui.linW(1f, Ui.WRAP, 0, 0, 0, 0));
        if (!job.finished()) {
            TextView cancel = Ui.text(c, "Cancel", 13, Theme.danger, Ui.MEDIUM);
            cancel.setPadding(Ui.dp(10), Ui.dp(4), 0, Ui.dp(4));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    job.cancel();
                }
            });
            head.addView(cancel, Ui.lin(Ui.WRAP, Ui.WRAP));
        }
        box.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP));

        String state;
        if (job.state == DownloadQueue.State.QUEUED) {
            state = "Waiting for a free slot";
        } else if (job.state == DownloadQueue.State.RUNNING) {
            state = Math.round(job.progress * 100) + "%";
        } else if (job.state == DownloadQueue.State.DONE) {
            state = "Saved to " + job.location;
        } else if (job.state == DownloadQueue.State.CANCELLED) {
            state = "Cancelled";
        } else {
            state = "Failed" + (job.message.isEmpty() ? "" : ": " + job.message);
        }
        TextView stateView = Ui.text(c, state, 12, job.state == DownloadQueue.State.FAILED ? Theme.danger : Theme.muted,
                Ui.REGULAR);
        box.addView(stateView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(4), 0, 0));

        if (!job.finished()) {
            ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(Math.round(job.progress * 100));
            bar.setProgressTintList(ColorStateList.valueOf(Theme.accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.surface3));
            box.addView(bar, Ui.lin(Ui.MATCH, Ui.dp(4), 0, Ui.dp(8), 0, 0));
        }
        return box;
    }

    // ================================================================= actions

    private void playAudio() {
        YouTubeVideo v = video;
        if (v == null) return;
        SyncoraService service = host.service();
        if (service == null) {
            host.toast("Syncora is still starting. Try again in a moment.");
            return;
        }
        YouTubeFormat audio = v.bestAudioForPlayback();
        if (audio == null) {
            host.toast("No playable audio stream was found for this video.");
            return;
        }
        service.playYouTube(v, audio);
        host.navigate(UiHost.TAB_PLAYER);
    }

    private void start(String kind, YouTubeVideo v, DownloadQueue.Work work) {
        DownloadQueue.Job job = queue.submit(kind, v.title, work);
        if (job.state == DownloadQueue.State.FAILED) host.toast(job.message);
        else host.toast(kind + " added to downloads");
    }

    // ================================================================= small views

    private static TextView sectionLabel(Context c, String text) {
        return Ui.text(c, text, 13, Theme.muted, Ui.MEDIUM);
    }

    private static View itemRow(Context c, String label, String action, View.OnClickListener onSave) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        Ui.setBackground(row, Ui.stroke(Theme.surface, Theme.line, 1, 14));
        row.addView(Ui.text(c, label, 14, Theme.text, Ui.REGULAR), Ui.linW(1f, Ui.WRAP, 0, 0, Ui.dp(8), 0));
        TextView save = Ui.secondaryButton(c, action);
        save.setOnClickListener(onSave);
        row.addView(save, Ui.lin(Ui.WRAP, Ui.WRAP));
        return row;
    }

    private static View captionRow(Context c, String name, View.OnClickListener srt, View.OnClickListener vtt) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        Ui.setBackground(row, Ui.stroke(Theme.surface, Theme.line, 1, 14));
        row.addView(Ui.text(c, name, 14, Theme.text, Ui.REGULAR), Ui.linW(1f, Ui.WRAP, 0, 0, Ui.dp(8), 0));
        TextView toSrt = Ui.secondaryButton(c, "SRT");
        toSrt.setOnClickListener(srt);
        row.addView(toSrt, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, Ui.dp(6), 0));
        TextView toVtt = Ui.secondaryButton(c, "VTT");
        toVtt.setOnClickListener(vtt);
        row.addView(toVtt, Ui.lin(Ui.WRAP, Ui.WRAP));
        return row;
    }
}
