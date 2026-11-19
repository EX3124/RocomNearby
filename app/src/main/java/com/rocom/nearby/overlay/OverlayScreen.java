package com.rocom.nearby.overlay;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

final class OverlayScreen {

    static Rect bounds(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        int rotation = Surface.ROTATION_0;
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            Display display = windowManager.getDefaultDisplay();
            if (display != null) {
                display.getRealMetrics(metrics);
                rotation = display.getRotation();
            }
        }
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        boolean landscape =
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        if ((width > height) != landscape) {
            int swap = width;
            width = height;
            height = swap;
        }
        return new Rect(0, 0, width, height);
    }

    static int longSide(Context context) {
        Rect bounds = bounds(context);
        return Math.max(bounds.width(), bounds.height());
    }
}
