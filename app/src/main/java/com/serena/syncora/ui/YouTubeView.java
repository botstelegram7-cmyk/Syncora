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
import com.serena.syncora.youtube.DownloadQueue;
import com.serena.syncora.youtube.DownloadStore;
import com.serena.syncora.youtube.YouTubeActions;
import com.serena.syncora.youtube.YouTubeClient;
import com.serena.syncora.youtube.YouTubeFormat;
import com.serena.syncora.youtube.YouTubeIds;
import com.serena.syncora.youtube.YouTubeVideo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTube: paste a link, then play the video's audio in sync (on the phone and every speaker), or save the video,
 * an audio stream, the thumbnail, lyrics or subtitles. Downloads run in the background and are listed below.
 */
final class YouTubeView extends SourceView {
    private static final ExecutorService IO = Executors.newCachedThreadPool();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DownloadQueue queue;
    private final EditText input;
    private final TextView status;
    private final LinearLayout details;
    private final LinearLayout jobsColumn;
    private final DownloadQueue.Listener queueListener = new DownloadQueue.Listener() {
        @Override
        public void onQueueChanged() {
            renderJobs();
        }
    };
    private YouTubeVideo video;
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
        input.setHint("Paste a YouTube link or video ID");
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
        TextView find = Ui.primaryButton(context, "Find");
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch();
            }
        });
        Ui.pressable(find);
        row.addView(find, Ui.lin(Ui.WRAP, Ui.WRAP));
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
    }

    // ================================================================= fetching

    private void fetch() {
        final String id = YouTubeIds.parse(input.getText().toString());
        if (id == null) {
            status.setText("That doesn't look like a YouTube link or video ID.");
            return;
        }
        final int request = ++fetchId;
        status.setText("Reading the video\u2026");
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YouTubeVideo found = YouTubeClient.fetch(id);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (request == fetchId) show(found);
                        }
                    });
                } catch (Exception e) {
                    final String why = e.getMessage() == null ? "network error" : e.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (request == fetchId) status.setText("Couldn't read this video: " + why);
                        }
                    });
                }
            }
        });
    }

    private void show(YouTubeVideo loaded) {
        video = loaded;
        status.setText(loaded.formats.isEmpty() ? "No downloadable streams were found for this video." : "");
        renderDetails();
    }

    // ================================================================= rendering

    private void renderDetails() {
        details.removeAllViews();
        final YouTubeVideo v = video;
        if (v == null) return;
        Context c = getContext();

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
        details.addView(Ui.text(c, "Plays the audio only, through this phone and any speakers in the room.",
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
        queue.submit(kind, v.title, work);
        host.toast(kind + " added to downloads");
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
