package com.rocom.nearby.entity;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

public class PingPongScroll extends HorizontalScrollView {

    private static final float SCROLL_DP_PER_SECOND = 24f;
    private static final long EDGE_PAUSE_MS = 900L;
    private static final long START_PAUSE_MS = 1600L;
    private static final long FRAME_MS = 33L;
    private static final long MAX_FRAME_MS = 66L;
    private final float pixelsPerMs;
    private boolean frameScheduled;
    private int direction = 1;
    private long pauseUntil;
    private long lastFrameAt;
    private int lastRange;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            frameScheduled = false;
            step();
        }
    };

    public PingPongScroll(Context context) {
        this(context, null);
    }

    public PingPongScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        setFillViewport(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(false);
        pixelsPerMs = SCROLL_DP_PER_SECOND * context.getResources().getDisplayMetrics().density / 1000f;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        syncScroll();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible)
            syncScroll();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrames();
        super.onDetachedFromWindow();
    }

    private int scrollRange() {
        if (getChildCount() == 0)
            return 0;
        View content = getChildAt(0);
        if (content.getVisibility() != VISIBLE)
            return 0;
        int visible = getWidth() - getPaddingLeft() - getPaddingRight();
        return Math.max(0, content.getMeasuredWidth() - visible);
    }

    private void syncScroll() {
        int range = scrollRange();
        if (range != lastRange) {
            lastRange = range;
            direction = 1;
            pauseUntil = SystemClock.uptimeMillis() + START_PAUSE_MS;
            scrollTo(0, 0);
        }
        if (range > 0 && isShown() && getWindowVisibility() == VISIBLE) {
            lastFrameAt = SystemClock.uptimeMillis();
            scheduleFrame();
        } else stopFrames();
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        postDelayed(tick, FRAME_MS);
    }

    private void stopFrames() {
        removeCallbacks(tick);
        frameScheduled = false;
    }

    private void step() {
        int range = scrollRange();
        if (range <= 0 || !isShown() || getWindowVisibility() != VISIBLE) {
            scrollTo(0, 0);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < pauseUntil) {
            lastFrameAt = now;
            scheduleFrame();
            return;
        }
        long elapsed = now - lastFrameAt;
        lastFrameAt = now;
        if (elapsed > MAX_FRAME_MS)
            elapsed = MAX_FRAME_MS;
        int delta = Math.round(pixelsPerMs * elapsed);
        if (delta < 1)
            delta = 1;
        int x = getScrollX() + direction * delta;
        if (x >= range) {
            x = range;
            direction = -1;
            pauseUntil = now + EDGE_PAUSE_MS;
        } else if (x <= 0) {
            x = 0;
            direction = 1;
            pauseUntil = now + START_PAUSE_MS;
        }
        scrollTo(x, 0);
        scheduleFrame();
    }
}
