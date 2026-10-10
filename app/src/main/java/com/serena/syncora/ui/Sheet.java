package com.serena.syncora.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.R;

/** A rounded bottom sheet used for pickers and quick panels. */
public final class Sheet {
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout root;
    private final LinearLayout body;
    private final TextView titleView;
    private final TextView subtitleView;
    private Runnable onDismiss;

    public Sheet(Context context, String title, String subtitle, int bottomInsetPx, int maxHeightPx) {
        this.context = context;
        dialog = new Dialog(context, R.style.SheetDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.topRounded(Theme.surface, 28));
        root.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(18) + bottomInsetPx);

        View handle = new View(context);
        handle.setBackground(Ui.rect(Theme.alpha(Theme.text, 56), 3));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = Ui.dp(14);
        root.addView(handle, handleParams);

        titleView = Ui.text(context, title, 20, Theme.text, Ui.BOLD);
        root.addView(titleView, Ui.lin(Ui.MATCH, Ui.WRAP));
        subtitleView = Ui.text(context, subtitle == null ? "" : subtitle, 13, Theme.muted, Ui.REGULAR);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(subtitleView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));

        MaxHeightScrollView scroll = new MaxHeightScrollView(context);
        scroll.setMaxHeight(maxHeightPx);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.6f);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                if (onDismiss != null) onDismiss.run();
            }
        });
    }

    public Context context() {
        return context;
    }

    public LinearLayout body() {
        return body;
    }

    public void setSubtitle(String text) {
        subtitleView.setText(text);
        subtitleView.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setOnDismiss(Runnable r) {
        onDismiss = r;
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void show() {
        try {
            dialog.show();
        } catch (RuntimeException ignored) {
            // the activity is going away
        }
    }

    public void dismiss() {
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
