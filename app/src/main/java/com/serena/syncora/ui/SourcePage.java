package com.serena.syncora.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.Source;

/** Second tab: the page of the current source. */
public final class SourcePage extends FrameLayout implements StateListener {
    private final UiHost host;
    private final LinearLayout header;
    private final TextView titleView;
    private final TextView subtitleView;
    private final LinearLayout chip;
    private final FrameLayout content;
    private final SourceView[] views = new SourceView[Source.values().length];
    private Source current;
    private SessionState state = new SessionState();

    public SourcePage(final UiHost host) {
        super(host.activity());
        this.host = host;
        Context c = host.activity();
        setBackgroundColor(Theme.bg);

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH));

        header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(16), Ui.dp(10));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = Ui.text(c, "My Music", 28, Theme.text, Ui.BOLD);
        titleView.setSingleLine(true);
        titles.addView(titleView, Ui.lin(Ui.MATCH, Ui.WRAP));
        subtitleView = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        subtitleView.setSingleLine(true);
        titles.addView(subtitleView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        header.addView(titles, Ui.lin(0, Ui.WRAP, 1f));
        chip = Ui.pill(c, 0, "Source", Theme.surface2, Theme.text);
        chip.addView(Ui.icon(c, R.drawable.ic_expand, 18, Theme.muted), Ui.lin(Ui.dp(18), Ui.dp(18), 6, 0, 0, 0));
        chip.setContentDescription("Choose the current source");
        chip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.source(host);
            }
        });
        header.addView(chip, Ui.lin(Ui.WRAP, Ui.WRAP));
        column.addView(header, Ui.lin(Ui.MATCH, Ui.WRAP));

        content = new FrameLayout(c);
        column.addView(content, Ui.lin(Ui.MATCH, 0, 1f));
    }

    public void setTopInset(int px) {
        header.setPadding(Ui.dp(20), Ui.dp(16) + px, Ui.dp(16), Ui.dp(10));
    }

    private SourceView viewFor(Source source) {
        int index = source.ordinal();
        if (views[index] == null) {
            Context c = host.activity();
            switch (source) {
                case RADIO:
                    views[index] = new RadioView(c, host);
                    break;
                case DLNA:
                    views[index] = new DlnaView(c, host);
                    break;
                case MIC:
                    views[index] = new MicView(c, host);
                    break;
                case SPEAKER:
                    views[index] = new SpeakerView(c, host);
                    break;
                default:
                    views[index] = new MusicView(c, host);
                    break;
            }
        }
        return views[index];
    }

    public void onShown() {
        SourceView v = current == null ? null : views[current.ordinal()];
        if (v != null) v.onShown();
    }

    public void onPermissionResult(int requestCode, boolean granted) {
        for (SourceView v : views) if (v != null) v.onPermissionResult(requestCode, granted);
    }

    public void release() {
        for (SourceView v : views) if (v != null) v.onRelease();
    }

    @Override
    public void onState(SessionState s) {
        state = s;
        if (s.source != current) {
            current = s.source;
            content.removeAllViews();
            SourceView view = viewFor(s.source);
            content.addView(view, Ui.frame(Ui.MATCH, Ui.MATCH));
            titleView.setText(pageTitle(s.source));
            Ui.fadeIn(view);
            view.onShown();
        }
        SourceView view = views[s.source.ordinal()];
        if (view != null) {
            view.onState(s);
            String sub = view.subtitle(s);
            if (!sub.contentEquals(subtitleView.getText())) subtitleView.setText(sub);
        }
    }

    private static String pageTitle(Source source) {
        switch (source) {
            case RADIO:
                return "Online Radio";
            case DLNA:
                return "DLNA Renderer";
            case MIC:
                return "Microphone / Aux";
            case SPEAKER:
                return "Speaker Mode";
            default:
                return "My Music";
        }
    }
}
