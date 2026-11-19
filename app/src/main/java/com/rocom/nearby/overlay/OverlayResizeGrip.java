package com.rocom.nearby.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;

import com.lzf.easyfloat.EasyFloat;

public final class OverlayResizeGrip extends View {

    static final int CORNER_START = 0;
    static final int CORNER_END = 1;
    private View content;
    private MinSize minSize;
    private String floatTag;
    private int corner = CORNER_END;
    private boolean resizing;
    private int startWidth;
    private int startHeight;
    private int startWindowX;
    private int startWindowY;
    private float downRawX;
    private float downRawY;

    public OverlayResizeGrip(Context context) {
        this(context, null);
    }

    public OverlayResizeGrip(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OverlayResizeGrip(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        
    }

    void bind(View content, String floatTag, int corner, MinSize minSize) {
        this.content = content;
        this.floatTag = floatTag;
        this.corner = corner;
        this.minSize = minSize;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return begin(event);
            case MotionEvent.ACTION_MOVE:
                if (!resizing) {
                    return false;
                }
                resize(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return finish();
            default:
                return resizing;
        }
    }

    private boolean begin(MotionEvent event) {
        if (content == null) {
            return false;
        }
        WindowManager.LayoutParams params = windowParams();
        if (params == null) {
            return false;
        }
        int width = content.getWidth();
        int height = content.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        startWidth = width;
        startHeight = height;
        startWindowX = params.x;
        startWindowY = params.y;
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        resizing = true;
        return true;
    }

    private void resize(MotionEvent event) {
        if (content == null) {
            return;
        }
        WindowManager.LayoutParams params = windowParams();
        if (params == null) {
            return;
        }
        int dx = Math.round(event.getRawX() - downRawX);
        int dy = Math.round(event.getRawY() - downRawY);
        int width = corner == CORNER_START ? startWidth - dx : startWidth + dx;
        int height = startHeight + dy;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxWidth = corner == CORNER_START
                ? startWindowX + startWidth
                : metrics.widthPixels - startWindowX;
        int maxHeight = metrics.heightPixels - startWindowY;
        width = clamp(width, minimumWidth(), maxWidth);
        height = clamp(height, minimumHeight(), maxHeight);
        ViewGroup.LayoutParams layout = content.getLayoutParams();
        if (layout != null && (layout.width != width || layout.height != height)) {
            layout.width = width;
            layout.height = height;
            content.setLayoutParams(layout);
        }
        if (corner == CORNER_START && floatTag != null) {
            int x = startWindowX + (startWidth - width);
            if (x != params.x) {
                EasyFloat.updateFloat(floatTag, x, params.y);
            }
        }
    }

    private boolean finish() {
        if (!resizing) {
            return false;
        }
        resizing = false;
        final View body = content;
        if (body != null) {
            body.post(() -> NearbyOverlay.onOverlayResized(body));
        }
        return true;
    }

    private WindowManager.LayoutParams windowParams() {
        if (content == null) {
            return null;
        }
        View root = content;
        ViewParent parent = root.getParent();
        while (parent instanceof View) {
            root = (View) parent;
            parent = root.getParent();
        }
        ViewGroup.LayoutParams params = root.getLayoutParams();
        return params instanceof WindowManager.LayoutParams
                ? (WindowManager.LayoutParams) params
                : null;
    }

    private static int clamp(int value, int min, int max) {
        int upper = Math.max(min, max);
        return Math.max(min, Math.min(upper, value));
    }

    private int minimumWidth() {
        return minSize != null ? minSize.minWidth(startWidth) : startWidth;
    }

    private int minimumHeight() {
        return minSize != null ? minSize.minHeight(startHeight) : startHeight;
    }

    static final class MinSize {

        private boolean measured;
        private int width;
        private int height;

        void capture(final View body) {
            if (tryMeasure(body)) {
                return;
            }
            body.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (tryMeasure(view)) {
                        view.removeOnLayoutChangeListener(this);
                    }
                }
            });
        }

        private boolean tryMeasure(View body) {
            if (measured) {
                return true;
            }
            int measuredWidth = body.getWidth();
            int measuredHeight = body.getHeight();
            if (measuredWidth <= 0 || measuredHeight <= 0) {
                return false;
            }
            width = measuredWidth;
            height = measuredHeight;
            measured = true;
            return true;
        }

        int minWidth(int fallback) {
            return measured ? width : fallback;
        }

        int minHeight(int fallback) {
            return measured ? height : fallback;
        }
    }
}
