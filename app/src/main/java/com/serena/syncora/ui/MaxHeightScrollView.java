package com.serena.syncora.ui;

import android.content.Context;
import android.widget.ScrollView;

/** A ScrollView that never grows taller than {@code maxHeight} (used inside bottom sheets). */
public final class MaxHeightScrollView extends ScrollView {
    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    public void setMaxHeight(int px) {
        maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int capped = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST));
    }
}
