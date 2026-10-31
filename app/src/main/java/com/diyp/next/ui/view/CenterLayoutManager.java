package com.diyp.next.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

/**
 * TV Viewport Center-Anchored LayoutManager.
 * Uses official Android LinearSmoothScroller to cleanly center items upon smooth navigation,
 * completely crash-proof on all Android versions from 5.0 to 14+.
 */
public class CenterLayoutManager extends LinearLayoutManager {

    public CenterLayoutManager(Context context) {
        super(context);
    }

    public CenterLayoutManager(Context context, int orientation, boolean reverseLayout) {
        super(context, orientation, reverseLayout);
    }

    public CenterLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        try {
            CenterSmoothScroller smoothScroller = new CenterSmoothScroller(recyclerView.getContext());
            smoothScroller.setTargetPosition(position);
            startSmoothScroll(smoothScroller);
        } catch (Throwable e) {
            super.smoothScrollToPosition(recyclerView, state, position);
        }
    }

    private static class CenterSmoothScroller extends LinearSmoothScroller {
        CenterSmoothScroller(Context context) {
            super(context);
        }

        @Override
        public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
            return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
        }

        @Override
        protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
            return 35f / displayMetrics.densityDpi;
        }
    }

    /**
     * Instantly center a target position in the RecyclerView.
     */
    public void scrollToPositionCentered(RecyclerView recyclerView, int position) {
        if (recyclerView == null || position < 0) return;
        try {
            int height = recyclerView.getHeight();
            if (height <= 0) {
                height = recyclerView.getResources().getDisplayMetrics().heightPixels;
            }
            int itemHeight = (int) (52 * recyclerView.getResources().getDisplayMetrics().density);
            int offset = Math.max(0, (height - itemHeight) / 2);
            scrollToPositionWithOffset(position, offset);
        } catch (Throwable ignored) {}
    }
}
