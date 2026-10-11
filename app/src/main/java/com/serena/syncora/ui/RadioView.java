package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.Source;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.radio.RadioCatalog;
import com.serena.syncora.radio.RadioDirectory;
import com.serena.syncora.radio.Station;

import java.util.ArrayList;
import java.util.List;

/** Internet radio: featured stations, favourites and a searchable directory. */
final class RadioView extends SourceView {
    private static final String[][] CHIPS = {
            {"featured", "Featured", "", ""}, {"favorites", "Favorites", "", ""}, {"top", "Top", "", ""},
            {"catalog", "Akashvani", "", ""}, {"country", "India", "", "IN"},
            {"tag", "Pop", "pop", ""}, {"tag", "Rock", "rock", ""},
            {"tag", "Hip-hop", "hip hop", ""}, {"tag", "Jazz", "jazz", ""}, {"tag", "Chill", "chillout", ""},
            {"tag", "Classical", "classical", ""}, {"tag", "News", "news", ""}
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ListView list;
    private final StationAdapter adapter = new StationAdapter();
    private final TextView infoView;
    private final EditText search;
    private final TextView[] chipViews = new TextView[CHIPS.length];
    private final List<Station> stations = new ArrayList<>();
    private int mode;
    private String query = "";
    private int requestId;
    private String playingUrl = "";
    private boolean playing;
    private boolean started;

    private final Runnable runSearch = new Runnable() {
        @Override
        public void run() {
            loadMode();
        }
    };

    RadioView(final Context c, final UiHost host) {
        super(c, host);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(4));

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout field = new LinearLayout(c);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        field.setBackground(Ui.stroke(Theme.surface, Theme.line, 1, 16));
        field.addView(Ui.icon(c, R.drawable.ic_search, 20, Theme.muted), Ui.lin(Ui.dp(20), Ui.dp(20), 0, 0, 10, 0));
        search = new EditText(c);
        search.setHint("Search stations worldwide");
        search.setHintTextColor(Theme.subtle);
        search.setTextColor(Theme.text);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(0, Ui.dp(13), 0, Ui.dp(13));
        field.addView(search, Ui.lin(0, Ui.WRAP, 1f));
        row.addView(field, Ui.lin(0, Ui.WRAP, 1f));
        ImageView add = Ui.iconButton(c, R.drawable.ic_add, 48, 24, Theme.onAccent, "Add a stream URL");
        add.setBackground(Ui.ripple(Ui.gradient(Theme.accentHi, Theme.accent, GradientDrawable.Orientation.TL_BR, 16), 16));
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                addStream();
            }
        });
        row.addView(add, Ui.lin(Ui.dp(48), Ui.dp(48), 10, 0, 0, 0));
        top.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                main.removeCallbacks(runSearch);
                main.postDelayed(runSearch, 550);
            }
        });

        HorizontalScrollView scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        for (int i = 0; i < CHIPS.length; i++) {
            final int index = i;
            TextView chip = Ui.text(c, CHIPS[i][1], 13, Theme.muted, Ui.MEDIUM);
            chip.setPadding(Ui.dp(15), Ui.dp(8), Ui.dp(15), Ui.dp(8));
            chip.setClickable(true);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = index;
                    search.setText("");
                    query = "";
                    updateChips();
                    loadMode();
                }
            });
            chipViews[i] = chip;
            chips.addView(chip, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        }
        scroller.addView(chips, new android.widget.FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        top.addView(scroller, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        column.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP));

        infoView = Ui.text(c, "", 13, Theme.muted, Ui.MEDIUM);
        infoView.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(6));
        column.addView(infoView, Ui.lin(Ui.MATCH, Ui.WRAP));

        list = new ListView(c);
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
                if (position >= 0 && position < stations.size()) play(stations.get(position));
            }
        });
        column.addView(list, Ui.lin(Ui.MATCH, 0, 1f));
        updateChips();
    }

    /** Starts a station on the player and shows the player. Every tap gives feedback, even when it cannot start. */
    private void play(Station st) {
        SyncoraService service = host.service();
        if (service == null) {
            host.toast("Syncora is still starting. Tap the station again in a moment.");
            return;
        }
        if (st.url.equals(playingUrl) && playing) {
            host.navigate(UiHost.TAB_PLAYER);
            return;
        }
        host.toast("Connecting to " + st.name + "\u2026");
        service.playStation(st);
        host.navigate(UiHost.TAB_PLAYER);
    }

    private void updateChips() {
        for (int i = 0; i < chipViews.length; i++) {
            boolean on = i == mode && query.isEmpty();
            chipViews[i].setTextColor(on ? Theme.onAccent : Theme.muted);
            chipViews[i].setBackground(on ? Ui.rect(Theme.accent, 18) : Ui.stroke(Theme.surface, Theme.line, 1, 18));
        }
    }

    @Override
    void onShown() {
        if (!started) {
            started = true;
            loadMode();
        }
    }

    @Override
    void onRelease() {
        main.removeCallbacks(runSearch);
    }

    private void loadMode() {
        final int id = ++requestId;
        updateChips();
        if (!query.isEmpty()) {
            infoView.setText("Searching\u2026");
            RadioDirectory.search(query, "", "", callback(id));
            return;
        }
        String[] chip = CHIPS[mode];
        switch (chip[0]) {
            case "featured":
                setStations(RadioDirectory.featured(), "Hand-picked stations that start fast");
                break;
            case "favorites": {
                List<Station> favorites = host.prefs().favoriteStations();
                setStations(favorites, favorites.isEmpty() ? "No favourites yet \u2014 tap the star next to a station" : "Your favourite stations");
                break;
            }
            case "top":
                infoView.setText("Loading the most popular stations\u2026");
                RadioDirectory.search("", "", "", callback(id));
                break;
            case "catalog": {
                List<Station> catalog = RadioCatalog.akashvani(getContext());
                setStations(catalog, catalog.size() + " All India Radio / Akashvani channels");
                break;
            }
            case "country":
                if ("IN".equals(chip[3])) {
                    List<Station> indian = RadioCatalog.india(getContext());
                    if (!indian.isEmpty()) {
                        setStations(indian, "Popular Indian stations · bundled for offline access");
                    } else {
                        infoView.setText("Loading stations…");
                        RadioDirectory.search("", "", chip[3], callback(id));
                    }
                } else {
                    infoView.setText("Loading stations…");
                    RadioDirectory.search("", "", chip[3], callback(id));
                }
                break;
            default:
                infoView.setText("Loading " + chip[1] + " stations\u2026");
                RadioDirectory.search("", chip[2], "", callback(id));
                break;
        }
    }

    private RadioDirectory.Callback callback(final int id) {
        return new RadioDirectory.Callback() {
            @Override
            public void onResult(List<Station> result, String error) {
                if (id != requestId) return;
                if (error != null) {
                    stations.clear();
                    adapter.notifyDataSetChanged();
                    infoView.setText(error);
                    return;
                }
                setStations(result, result.isEmpty() ? "No stations found" : result.size() + " stations");
            }
        };
    }

    private void setStations(List<Station> list, String info) {
        stations.clear();
        stations.addAll(list);
        infoView.setText(info);
        adapter.notifyDataSetChanged();
    }

    private void addStream() {
        Sheets.input(host, "Add a station", "Paste the address of an MP3, AAC or HLS radio stream", "https://example.com/stream",
                "", Sheets.urlInput(), "Play", new Sheets.TextListener() {
                    @Override
                    public void onText(String text) {
                        String url = text.trim();
                        if (url.isEmpty()) return;
                        if (!url.contains("://")) url = "http://" + url;
                        Uri uri = Uri.parse(url);
                        if (uri.getHost() == null) {
                            host.toast("That doesn't look like a stream address.");
                            return;
                        }
                        Station st = new Station("custom-" + Integer.toHexString(url.hashCode()), uri.getHost(), url, "custom", "", "", 0);
                        if (!host.prefs().isFavorite(st)) host.prefs().toggleFavorite(st);
                        play(st);
                        if (CHIPS[mode][0].equals("favorites")) loadMode();
                    }
                });
    }

    @Override
    String subtitle(SessionState s) {
        if (s.hasMedia && s.source == Source.RADIO) return "Now playing: " + s.stationName;
        return "Streamed once, heard on every speaker";
    }

    @Override
    public void onState(SessionState s) {
        SyncoraService service = host.service();
        String url = "";
        if (s.source == Source.RADIO && service != null && service.currentStation() != null && s.hasMedia) url = service.currentStation().url;
        boolean changed = !url.equals(playingUrl) || s.playing != playing;
        playingUrl = url;
        playing = s.playing;
        if (changed) adapter.notifyDataSetChanged();
    }

    private final class StationAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return stations.size();
        }

        @Override
        public Object getItem(int position) {
            return stations.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Context c = parent.getContext();
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(8), Ui.dp(7), Ui.dp(4), Ui.dp(7));
                TextView avatar = new TextView(c);
                avatar.setGravity(Gravity.CENTER);
                avatar.setTextColor(0xFFFFFFFF);
                avatar.setTextSize(15);
                avatar.setTypeface(Ui.BOLD);
                row.addView(avatar, Ui.lin(Ui.dp(50), Ui.dp(50), 0, 0, 14, 0));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = Ui.text(c, "", 15, Theme.text, Ui.MEDIUM);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP));
                TextView sub = Ui.text(c, "", 12, Theme.muted, Ui.REGULAR);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
                row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
                EqualizerView eq = new EqualizerView(c);
                row.addView(eq, Ui.lin(Ui.dp(18), Ui.dp(16), 6, 0, 6, 0));
                ImageView star = Ui.iconButton(c, R.drawable.ic_star_outline, 44, 22, Theme.muted, "Favourite");
                row.addView(star, Ui.lin(Ui.dp(44), Ui.dp(44)));
            }
            final Station st = stations.get(position);
            boolean current = st.url.equals(playingUrl);
            TextView avatar = (TextView) row.getChildAt(0);
            int[] colors = Theme.artColors(st.colorIndex());
            avatar.setBackground(Ui.gradient(colors[0], colors[1], GradientDrawable.Orientation.TL_BR, 25));
            avatar.setText(st.initials());
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView name = (TextView) texts.getChildAt(0);
            name.setText(st.name);
            name.setTextColor(current ? Theme.accent : Theme.text);
            ((TextView) texts.getChildAt(1)).setText(st.subtitle());
            EqualizerView eq = (EqualizerView) row.getChildAt(2);
            eq.setVisibility(current ? VISIBLE : GONE);
            eq.setPlaying(current && playing);
            final ImageView star = (ImageView) row.getChildAt(3);
            boolean favorite = host.prefs().isFavorite(st);
            star.setImageResource(favorite ? R.drawable.ic_star : R.drawable.ic_star_outline);
            star.setColorFilter(favorite ? Theme.accent : Theme.muted);
            star.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = host.prefs().toggleFavorite(st);
                    star.setImageResource(now ? R.drawable.ic_star : R.drawable.ic_star_outline);
                    star.setColorFilter(now ? Theme.accent : Theme.muted);
                    if (CHIPS[mode][0].equals("favorites")) loadMode();
                }
            });
            row.setBackground(Ui.ripple(current ? Ui.rect(Theme.accentSoft, 16) : null, 16));
            // The whole row is the target: a tap anywhere on it starts the station (the star keeps its own tap).
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    play(st);
                }
            });
            return row;
        }
    }
}
