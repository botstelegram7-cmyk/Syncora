package com.serena.syncora.ui;

import android.content.Context;
import android.widget.FrameLayout;

import com.serena.syncora.SessionState;

/** Content of the second tab for one source (library, radio, DLNA, microphone, speaker). */
abstract class SourceView extends FrameLayout implements StateListener {
    protected final UiHost host;

    SourceView(Context context, UiHost host) {
        super(context);
        this.host = host;
    }

    /** A short line under the page title. */
    abstract String subtitle(SessionState state);

    /** The tab became visible. */
    void onShown() {
    }

    /** Result of a permission request this view started. */
    void onPermissionResult(int requestCode, boolean granted) {
    }

    /** Called when the activity is destroyed or the page is rebuilt. */
    void onRelease() {
    }
}
