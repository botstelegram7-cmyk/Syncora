package com.serena.syncora.ui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.Source;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.audio.MusicLibrary;
import com.serena.syncora.core.Track;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** All the music on the device, shown inside the app (no file picker). */
final class MusicView extends SourceView {
    static final int REQUEST_STORAGE = 4101;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ListView list;
    private final TrackAdapter adapter = new TrackAdapter();
    private final LinearLayout permissionCard;
    private final TextView emptyView;
    private final TextView countView;
    private final EditText search;
    private final View clearButton;
    private final TextView[] sortChips = new TextView[3];
    private final View toolbar;

    private final List<Track> all = new ArrayList<>();
    private final List<Track> shown = new ArrayList<>();
    private boolean loaded;
    private boolean loading;
    private String sort = "title";
    private String query = "";
    private SessionState state = new SessionState();
    private long playingId = -1;
    private boolean playing;
    private boolean registered;

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 1200);
        }
    };

    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    private final Runnable applyFilter = new Runnable() {
        @Override
        public void run() {
            refilter();
        }
    };

    MusicView(final Context context, UiHost host) {
        super(context, host);
        sort = host.prefs().librarySort();

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH));

        toolbar = buildToolbar(context);
        column.addView(toolbar, Ui.lin(Ui.MATCH, Ui.WRAP));

        countView = Ui.text(context, "", 12, Theme.subtle, Ui.MEDIUM);
        countView.setPadding(Ui.dp(22), Ui.dp(4), Ui.dp(22), Ui.dp(6));
        column.addView(countView, Ui.lin(Ui.MATCH, Ui.WRAP));

        list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(10), 0, Ui.dp(10), Ui.dp(16));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                playAt(position);
            }
        });
        column.addView(list, Ui.lin(Ui.MATCH, 0, 1f));

        permissionCard = buildPermissionCard(context);
        permissionCard.setVisibility(GONE);
        FrameLayout.LayoutParams cardParams = Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER);
        cardParams.leftMargin = Ui.dp(24);
        cardParams.rightMargin = Ui.dp(24);
        addView(permissionCard, cardParams);

        emptyView = Ui.text(context, "", 14, Theme.muted, Ui.REGULAR);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Ui.dp(40), 0, Ui.dp(40), 0);
        emptyView.setVisibility(GONE);
        addView(emptyView, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER));
        clearButton = toolbar.findViewWithTag("clear");
        search = (EditText) toolbar.findViewWithTag("search");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                clearButton.setVisibility(query.isEmpty() ? GONE : VISIBLE);
                main.removeCallbacks(applyFilter);
                main.postDelayed(applyFilter, 120);
            }
        });
        updateChips();
    }

    private View buildToolbar(final Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(4));

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout field = new LinearLayout(c);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(Ui.dp(14), 0, Ui.dp(6), 0);
        field.setBackground(Ui.stroke(Theme.surface, Theme.line, 1, 16));
        field.addView(Ui.icon(c, R.drawable.ic_search, 20, Theme.muted), Ui.lin(Ui.dp(20), Ui.dp(20), 0, 0, 10, 0));
        EditText edit = new EditText(c);
        edit.setTag("search");
        edit.setHint("Search songs, artists, albums");
        edit.setHintTextColor(Theme.subtle);
        edit.setTextColor(Theme.text);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setBackground(null);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setPadding(0, Ui.dp(13), 0, Ui.dp(13));
        field.addView(edit, Ui.lin(0, Ui.WRAP, 1f));
        ImageView clear = Ui.iconButton(c, R.drawable.ic_close, 40, 18, Theme.muted, "Clear search");
        clear.setTag("clear");
        clear.setVisibility(GONE);
        clear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ((EditText) toolbar.findViewWithTag("search")).setText("");
            }
        });
        field.addView(clear, Ui.lin(Ui.dp(40), Ui.dp(40)));
        row.addView(field, Ui.lin(0, Ui.WRAP, 1f));

        TextView shuffleAll = Ui.primaryButton(c, "Shuffle");
        shuffleAll.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(16), Ui.dp(13));
        shuffleAll.setContentDescription("Shuffle all songs");
        shuffleAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffleAll();
            }
        });
        row.addView(shuffleAll, Ui.lin(Ui.WRAP, Ui.WRAP, 10, 0, 0, 0));
        box.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));

        LinearLayout chips = new LinearLayout(c);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        final String[] ids = {"title", "artist", "album"};
        String[] labels = {"Title", "Artist", "Album"};
        chips.addView(Ui.text(c, "Sort", 12, Theme.subtle, Ui.MEDIUM), Ui.lin(Ui.WRAP, Ui.WRAP, 4, 0, 10, 0));
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            TextView chip = Ui.text(c, labels[i], 12, Theme.muted, Ui.MEDIUM);
            chip.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
            chip.setClickable(true);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sort = id;
                    MusicView.this.host.prefs().setLibrarySort(id);
                    updateChips();
                    refilter();
                }
            });
            sortChips[i] = chip;
            chips.addView(chip, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, 6, 0));
        }
        box.addView(chips, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        return box;
    }

    private void updateChips() {
        String[] ids = {"title", "artist", "album"};
        for (int i = 0; i < ids.length; i++) {
            boolean on = ids[i].equals(sort);
            sortChips[i].setTextColor(on ? Theme.onAccent : Theme.muted);
            sortChips[i].setBackground(on ? Ui.rect(Theme.accent, 14) : Ui.stroke(0, Theme.line, 1, 14));
        }
    }

    private LinearLayout buildPermissionCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(24), Ui.dp(28), Ui.dp(24), Ui.dp(24));
        card.setBackground(Ui.card(24));
        ImageView icon = Ui.icon(c, R.drawable.ic_library, 40, Theme.accent);
        card.addView(icon, Ui.lin(Ui.dp(40), Ui.dp(40), 0, 0, 0, 14));
        card.addView(Ui.text(c, "Show the music on this phone", 18, Theme.text, Ui.BOLD), Ui.lin(Ui.WRAP, Ui.WRAP));
        TextView body = Ui.text(c, "Syncora lists the songs stored on this device so you can play them for every speaker in the room. "
                + "Nothing is uploaded.", 13, Theme.muted, Ui.REGULAR);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.15f);
        card.addView(body, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 8, 0, 18));
        TextView allow = Ui.primaryButton(c, "Allow access");
        allow.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.requestPermission(MusicLibrary.permission(), REQUEST_STORAGE);
            }
        });
        card.addView(allow, Ui.lin(Ui.WRAP, Ui.WRAP));
        return card;
    }

    // ---------------------------------------------------------------- loading & filtering

    @Override
    void onShown() {
        if (!MusicLibrary.hasPermission(getContext())) {
            showPermission(true);
            return;
        }
        showPermission(false);
        if (!loaded && !loading) load();
    }

    @Override
    void onPermissionResult(int requestCode, boolean granted) {
        if (requestCode != REQUEST_STORAGE) return;
        showPermission(!granted);
        if (granted) load();
    }

    private void showPermission(boolean show) {
        permissionCard.setVisibility(show ? VISIBLE : GONE);
        toolbar.setVisibility(show ? GONE : VISIBLE);
        countView.setVisibility(show ? GONE : VISIBLE);
        list.setVisibility(show ? GONE : VISIBLE);
        if (show) emptyView.setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!registered && MusicLibrary.hasPermission(getContext())) {
            try {
                getContext().getContentResolver().registerContentObserver(MusicLibrary.collection(), true, observer);
                registered = true;
            } catch (RuntimeException ignored) {
                registered = false;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(reload);
        if (registered) {
            getContext().getContentResolver().unregisterContentObserver(observer);
            registered = false;
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        if (!loaded) {
            emptyView.setText("Loading your music\u2026");
            emptyView.setVisibility(VISIBLE);
        }
        final Context context = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Track> tracks = MusicLibrary.loadAll(context);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        loaded = true;
                        all.clear();
                        all.addAll(tracks);
                        refilter();
                        if (!registered && isAttachedToWindow() && MusicLibrary.hasPermission(context)) onAttachedToWindow();
                    }
                });
            }
        }, "syncora-library").start();
    }

    private void refilter() {
        shown.clear();
        String q = query.toLowerCase(Locale.getDefault());
        for (Track t : all) {
            if (q.isEmpty() || t.title.toLowerCase(Locale.getDefault()).contains(q)
                    || t.artist.toLowerCase(Locale.getDefault()).contains(q) || t.album.toLowerCase(Locale.getDefault()).contains(q)) {
                shown.add(t);
            }
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        final String mode = sort;
        Collections.sort(shown, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                if ("artist".equals(mode)) {
                    int r = collator.compare(a.artist.isEmpty() ? "\uFFFF" : a.artist, b.artist.isEmpty() ? "\uFFFF" : b.artist);
                    return r != 0 ? r : collator.compare(a.title, b.title);
                }
                if ("album".equals(mode)) {
                    int r = collator.compare(a.album.isEmpty() ? "\uFFFF" : a.album, b.album.isEmpty() ? "\uFFFF" : b.album);
                    return r != 0 ? r : collator.compare(a.title, b.title);
                }
                return collator.compare(a.title, b.title);
            }
        });
        adapter.notifyDataSetChanged();
        int n = shown.size();
        countView.setText(query.isEmpty() ? n + (n == 1 ? " song" : " songs") : n + (n == 1 ? " result" : " results"));
        if (n == 0) {
            emptyView.setText(all.isEmpty() ? "No music found on this device yet." : "Nothing matches \u201C" + query + "\u201D");
            emptyView.setVisibility(VISIBLE);
        } else {
            emptyView.setVisibility(GONE);
        }
    }

    private void playAt(int position) {
        SyncoraService service = host.service();
        if (service == null || position < 0 || position >= shown.size()) return;
        Track t = shown.get(position);
        if (t.id == playingId && state.source == Source.MUSIC) {
            host.navigate(UiHost.TAB_PLAYER);
            if (!playing) service.togglePlay();
            return;
        }
        service.playTracks(new ArrayList<>(shown), position);
        host.navigate(UiHost.TAB_PLAYER);
    }

    private void shuffleAll() {
        SyncoraService service = host.service();
        if (service == null || shown.isEmpty()) {
            host.toast("No songs to shuffle yet.");
            return;
        }
        service.setShuffle(true);
        service.playTracks(new ArrayList<>(shown), new Random().nextInt(shown.size()));
        host.navigate(UiHost.TAB_PLAYER);
    }

    @Override
    String subtitle(SessionState s) {
        int n = all.size();
        if (!MusicLibrary.hasPermission(getContext())) return "Allow access to see your songs";
        if (!loaded) return "Loading\u2026";
        return n == 1 ? "1 song on this device" : n + " songs on this device";
    }

    @Override
    public void onState(SessionState s) {
        state = s;
        long id = s.source == Source.MUSIC && host.service() != null ? host.service().currentTrackId() : -1;
        boolean changed = id != playingId || s.playing != playing;
        playingId = id;
        playing = s.playing;
        if (changed) adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- adapter

    private static final class Holder {
        ImageView thumb;
        TextView title;
        TextView artist;
        TextView duration;
        EqualizerView eq;
        long trackId = -1;
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Holder h;
            View row = convertView;
            if (row == null) {
                Context c = parent.getContext();
                LinearLayout line = new LinearLayout(c);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(Ui.dp(8), Ui.dp(7), Ui.dp(10), Ui.dp(7));
                line.setBackground(Ui.ripple(null, 16));
                h = new Holder();
                h.thumb = new ImageView(c);
                h.thumb.setClipToOutline(true);
                h.thumb.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dpf(12));
                    }
                });
                line.addView(h.thumb, Ui.lin(Ui.dp(52), Ui.dp(52), 0, 0, 14, 0));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.title = Ui.text(c, "", 15, Theme.text, Ui.MEDIUM);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(h.title, Ui.lin(Ui.MATCH, Ui.WRAP));
                h.artist = Ui.text(c, "", 12, Theme.muted, Ui.REGULAR);
                h.artist.setSingleLine(true);
                h.artist.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(h.artist, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
                line.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
                h.duration = Ui.text(c, "", 12, Theme.subtle, Ui.MEDIUM);
                line.addView(h.duration, Ui.lin(Ui.WRAP, Ui.WRAP, 10, 0, 0, 0));
                h.eq = new EqualizerView(c);
                line.addView(h.eq, Ui.lin(Ui.dp(18), Ui.dp(16), 10, 0, 0, 0));
                line.setTag(h);
                row = line;
            } else {
                h = (Holder) row.getTag();
            }
            final Track t = shown.get(position);
            h.trackId = t.id;
            boolean current = t.id == playingId;
            h.title.setText(t.title);
            h.title.setTextColor(current ? Theme.accent : Theme.text);
            String sub = t.subtitle();
            h.artist.setText(sub.isEmpty() ? "Unknown artist" : sub);
            h.duration.setText(t.durationMs > 0 ? Ui.formatTime(t.durationMs) : "");
            h.duration.setVisibility(current ? GONE : VISIBLE);
            h.eq.setVisibility(current ? VISIBLE : GONE);
            h.eq.setPlaying(current && playing);
            row.setBackground(Ui.ripple(current ? Ui.rect(Theme.accentSoft, 16) : null, 16));

            Bitmap cached = ThumbCache.peek(t.artKey());
            if (cached != null) {
                h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.thumb.setPadding(0, 0, 0, 0);
                h.thumb.setImageBitmap(cached);
            } else {
                int[] colors = Theme.artColors(t.artKey());
                h.thumb.setBackground(Ui.gradient(colors[0], colors[1], android.graphics.drawable.GradientDrawable.Orientation.TL_BR, 12));
                h.thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.thumb.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
                h.thumb.setImageResource(R.drawable.ic_note);
                h.thumb.setColorFilter(0xCCFFFFFF);
                if (!ThumbCache.known(t.artKey())) {
                    ThumbCache.load(parent.getContext(), t, 160, new ThumbCache.Callback() {
                        @Override
                        public void onLoaded(Bitmap bitmap) {
                            if (h.trackId != t.id) return;
                            h.thumb.clearColorFilter();
                            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            h.thumb.setPadding(0, 0, 0, 0);
                            h.thumb.setImageBitmap(bitmap);
                        }
                    });
                }
            }
            if (cached != null) h.thumb.clearColorFilter();
            return row;
        }
    }
}
