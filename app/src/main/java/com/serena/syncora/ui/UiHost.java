package com.serena.syncora.ui;

import android.app.Activity;

import com.serena.syncora.Prefs;
import com.serena.syncora.SessionState;
import com.serena.syncora.SyncoraService;

/** What the screens and sheets need from the activity that hosts them. */
public interface UiHost {
    int TAB_PLAYER = 0;
    int TAB_SOURCE = 1;
    int TAB_DEVICES = 2;
    int TAB_SETTINGS = 3;

    Activity activity();

    /** Null until the service connection is established. */
    SyncoraService service();

    Prefs prefs();

    SessionState state();

    void addStateListener(StateListener listener);

    void removeStateListener(StateListener listener);

    void toast(String message);

    void showSheet(Sheet sheet);

    void navigate(int tab);

    boolean hasPermission(String permission);

    void requestPermission(String permission, int requestCode);

    /** Re-creates every screen (after a theme / accent change). */
    void themeChanged();

    int bottomInset();

    int maxSheetHeight();
}
