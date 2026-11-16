package com.rocom.nearby.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.rocom.nearby.MainActivity;
import com.rocom.nearby.R;

import java.lang.ref.WeakReference;

/**
 * Owns the system overlay created by EasyFloat.
 *
 * <p>{@link ShowPattern#ALL_TIME} keeps the compact handle above the game while
 * the nearby screen is in the background. The real nearby list/map will replace
 * the handle once the pcap tracker is wired up; for now the overlay is a
 * draggable title card that also proves the ALL_TIME lifecycle works.</p>
 */
public final class NearbyOverlay {
    private static final String TAG = "nearby-overlay";

    private static WeakReference<Callback> callback = new WeakReference<>(null);
    private static boolean requesting;

    public interface Callback {
        void onOverlayStateChanged();
    }

    private NearbyOverlay() {
    }

    public static boolean isShowing() {
        return EasyFloat.isShow(TAG);
    }

    public static void show(Activity activity) {
        if (isShowing() || requesting) {
            return;
        }
        View anchor = activity.findViewById(R.id.float_button);
        if (anchor != null && anchor.getWidth() <= 0) {
            // The activity has not been laid out yet; retry once it has a size
            // so the overlay can be positioned under the floating button.
            anchor.post(() -> show(activity));
            return;
        }
        requesting = true;
        final Context appContext = activity.getApplicationContext();
        int[] anchorLocation = new int[2];
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int statusBarInset = 0;
        int offsetX = 0;
        int offsetY = dp(activity, 2);
        View root = activity.findViewById(R.id.root);
        if (root != null) {
            statusBarInset = root.getPaddingTop();
        }
        if (anchor != null) {
            anchor.getLocationOnScreen(anchorLocation);
            offsetX = anchorLocation[0] + anchor.getWidth() - screenWidth;
            offsetY = anchorLocation[1] + anchor.getHeight()
                    - statusBarInset + dp(activity, 2);
        }
        try {
            EasyFloat.with(activity)
                    .setLayout(R.layout.overlay, view -> bindView(appContext, view))
                    .setShowPattern(ShowPattern.ALL_TIME)
                    .setGravity(Gravity.END | Gravity.TOP,
                            offsetX, Math.max(0, offsetY))
                    .setTag(TAG)
                    .setDragEnable(true)
                    .registerCallbacks(new OnFloatCallbacks() {
                        @Override
                        public void createdResult(boolean isCreated, String msg, View view) {
                            requesting = false;
                            if (!isCreated) {
                                Toast.makeText(appContext, R.string.toast_overlay_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                            notifyState();
                        }

                        @Override
                        public void show(View view) {
                            notifyState();
                        }

                        @Override
                        public void hide(View view) {
                            notifyState();
                        }

                        @Override
                        public void dismiss() {
                            notifyState();
                        }

                        @Override
                        public void touchEvent(View view, MotionEvent event) {
                        }

                        @Override
                        public void drag(View view, MotionEvent event) {
                        }

                        @Override
                        public void dragEnd(View view) {
                        }
                    })
                    .show();
        } catch (Throwable e) {
            requesting = false;
            Toast.makeText(appContext, R.string.toast_overlay_failed, Toast.LENGTH_SHORT).show();
            notifyState();
        }
    }

    public static void dismiss() {
        requesting = false;
        EasyFloat.dismiss(TAG, true);
        notifyState();
    }

    public static void setCallback(Callback newCallback) {
        callback = new WeakReference<>(newCallback);
    }

    public static void clearCallback(Callback oldCallback) {
        if (callback.get() == oldCallback) {
            callback.clear();
        }
    }

    private static void bindView(Context appContext, View view) {
        View close = view.findViewById(R.id.float_close);
        if (close != null) {
            close.setOnClickListener(clicked -> clicked.post(NearbyOverlay::dismiss));
        }
        view.setOnClickListener(clicked -> openApp(appContext));
    }

    private static void openApp(Context appContext) {
        Intent intent = new Intent(appContext, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            appContext.startActivity(intent);
        } catch (Throwable ignored) {
            // The task may be gone; the overlay itself stays usable.
        }
    }

    private static void notifyState() {
        Callback target = callback.get();
        if (target != null) {
            target.onOverlayStateChanged();
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
