package com.rocom.nearby.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;

import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.rocom.nearby.R;
import com.rocom.nearby.entity.NearbyListView;
import com.rocom.nearby.map.NearbyRuntime;

import java.lang.ref.WeakReference;

public final class NearbyOverlay {

    private static final String TAG = "nearby-overlay";
    private static boolean requesting;
    private static final String PREFS = "nearby";
    private static final String KEY_OPACITY = "opacity";
    private static final int CARD_WIDTH_DP = 264;
    private static final int VISIBLE_ROWS = 2;
    private static final int BOTTOM_INSET_DP = 12;
    private static WeakReference<View> floatView = new WeakReference<>(null);
    private static boolean hadCards;
    private static int visibleRows;
    private static final NearbyRuntime.Listener SNAPSHOT_LISTENER = NearbyOverlay::renderSnapshot;
    private static DisplayManager displayManager;
    private static DisplayManager.DisplayListener displaySizeListener;

    public static boolean isShowing() {
        return EasyFloat.isShow(TAG);
    }

    public static void show(Activity activity) {
        if (isShowing() || requesting) return;
        View anchor = activity.findViewById(R.id.overlay_button);
        if (anchor != null && anchor.getWidth() <= 0) {
            anchor.post(() -> show(activity));
            return;
        }
        requesting = true;
        final Context appContext = activity.getApplicationContext();
        int[] anchorLocation = new int[2];
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int statusBarInset = 0;
        int startX = Math.max(0, screenWidth - windowWidth(activity));
        int startY = dp(activity, 2);
        View root = activity.findViewById(R.id.root);
        if (root != null)
            statusBarInset = root.getPaddingTop();
        if (anchor != null) {
            anchor.getLocationOnScreen(anchorLocation);
            startX = Math.max(0, anchorLocation[0] + anchor.getWidth() - windowWidth(activity));
            startY = anchorLocation[1] + anchor.getHeight() - statusBarInset + dp(activity, 2);
        }
        try {
            EasyFloat.with(activity)
                    .setLayout(R.layout.overlay, view -> bindView(appContext, view))
                    .setShowPattern(ShowPattern.ALL_TIME)
                    .setLocation(startX, Math.max(0, startY))
                    .setTag(TAG)
                    .setDragEnable(true)
                    .registerCallbacks(new OnFloatCallbacks() {
                        @Override
                        public void createdResult(boolean isCreated, String msg, View view) {
                            requesting = false;
                        }

                        @Override
                        public void show(@NonNull View view) {
                            attach(view);
                        }

                        @Override
                        public void hide(@NonNull View view) {
                            detach();
                        }

                        @Override
                        public void dismiss() {
                            detach();
                        }

                        @Override
                        public void touchEvent(@NonNull View view, @NonNull MotionEvent event) {
                            handOffDrag(view, event);
                        }

                        @Override
                        public void drag(@NonNull View view, @NonNull MotionEvent motionEvent) {
                        }

                        @Override
                        public void dragEnd(@NonNull View view) {
                        }
                    })
                    .show();
        } catch (Throwable e) {
            requesting = false;
        }
    }

    public static void dismiss() {
        requesting = false;
        detach();
        EasyFloat.dismiss(TAG, true);
    }

    private static void bindView(Context appContext, View view) {
        detach();
        View close = view.findViewById(R.id.overlay_close);
        if (close != null)
            close.setOnClickListener(clicked -> clicked.post(NearbyOverlay::dismiss));
        bindOpacity(appContext, view);
        NearbyListView cards = view.findViewById(R.id.overlay_cards);
        if (cards != null) {
            cards.setAlertPublishing(false);
            cards.setHeadFlush(true);
            cards.setBackground(null);
        }
        View scroller = view.findViewById(R.id.overlay_scroll);
        if (scroller != null)
            scroller.setPadding(0, 0, 0, dp(appContext, BOTTOM_INSET_DP));
        bindResizeGrips(view);
        attach(view);
    }

    private static void bindResizeGrips(View view) {
        View body = contentRoot(view);
        OverlayResizeGrip.MinSize minSize = new OverlayResizeGrip.MinSize();
        minSize.capture(body);
        OverlayResizeGrip start = view.findViewById(R.id.overlay_resize_start);
        if (start != null)
            start.bind(body, TAG, OverlayResizeGrip.CORNER_START, minSize);
        OverlayResizeGrip end = view.findViewById(R.id.overlay_resize_end);
        if (end != null)
            end.bind(body, TAG, OverlayResizeGrip.CORNER_END, minSize);
    }

    private static View contentRoot(View view) {
        View body = view.findViewById(R.id.overlay_body);
        return body != null ? body : view;
    }

    private static void bindOpacity(Context appContext, View view) {
        SeekBar seek = view.findViewById(R.id.overlay_opacity_seek);
        if (seek == null) return;
        seek.setVisibility(View.GONE);
        final SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT > 26)
            seek.setMin(5);
        seek.setProgress(prefs.getInt(KEY_OPACITY, 100));
        view.setAlpha((float) seek.getProgress() / 100);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                view.setAlpha(((float) progress / 100));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(KEY_OPACITY, bar.getProgress()).apply();
            }
        });
        View icon = view.findViewById(R.id.overlay_opacity_icon);
        if (icon != null)
            icon.setOnClickListener(clicked -> seek.setVisibility(seek.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private static void attach(View view) {
        floatView = new WeakReference<>(view);
        watchDisplaySize(view);
        NearbyRuntime.addListener(SNAPSHOT_LISTENER);
        renderSnapshot(NearbyRuntime.current());
    }

    private static void detach() {
        unwatchDisplaySize();
        NearbyRuntime.removeListener(SNAPSHOT_LISTENER);
        floatView = new WeakReference<>(null);
        hadCards = false;
        visibleRows = 0;
    }

    private static void watchDisplaySize(View view) {
        unwatchDisplaySize();
        Context host = view.getContext().getApplicationContext() != null ? view.getContext().getApplicationContext() : view.getContext();
        DisplayManager manager = (DisplayManager) host.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) return;
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return;
                View current = floatView.get();
                if (current != null)
                    current.post(() -> fitToScreen(current));
            }
        };
        manager.registerDisplayListener(listener, new Handler(Looper.getMainLooper()));
        displayManager = manager;
        displaySizeListener = listener;
    }

    private static void unwatchDisplaySize() {
        if (displayManager == null || displaySizeListener == null) return;
        displayManager.unregisterDisplayListener(displaySizeListener);
        displayManager = null;
        displaySizeListener = null;
    }

    private static void fitToScreen(View view) {
        View body = contentRoot(view);
        ViewGroup.LayoutParams layout = body.getLayoutParams();
        if (layout == null || layout.width <= 0 || layout.height <= 0) return;
        WindowManager.LayoutParams params = OverlayResizeGrip.windowParamsOf(body);
        int x = params != null ? Math.max(0, params.x) : 0;
        int y = params != null ? Math.max(0, params.y) : 0;
        Point size = OverlayResizeGrip.resolveScreenSize(view.getContext());
        int maxWidth = Math.max(1, size.x - x);
        int maxHeight = Math.max(1, size.y - y);
        if (layout.width <= maxWidth && layout.height <= maxHeight) return;
        layout.width = Math.min(layout.width, maxWidth);
        layout.height = Math.min(layout.height, maxHeight);
        body.setLayoutParams(layout);
        EasyFloat.updateFloat(TAG, x, y, layout.width, layout.height);
    }

    private static void renderSnapshot(NearbyRuntime.Snapshot snapshot) {
        View view = floatView.get();
        if (view == null) return;
        NearbyListView cards = view.findViewById(R.id.overlay_cards);
        ScrollView scroller = view.findViewById(R.id.overlay_scroll);
        View empty = view.findViewById(R.id.overlay_empty);
        if (cards == null || scroller == null) return;
        cards.bind(snapshot.entities, Double.NaN, Double.NaN);
        boolean hasCards = cards.getVisibility() == View.VISIBLE;
        if (empty != null)
            empty.setVisibility(hasCards ? View.GONE : View.VISIBLE);
        scroller.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        if (hasCards) {
            if (!hadCards)
                scroller.scrollTo(0, 0);
            fitCardsHeight(view, cards, scroller);
        } else visibleRows = 0;
        hadCards = hasCards;
    }

    private static void fitCardsHeight(View view, NearbyListView cards, ScrollView scroller) {
        Context context = view.getContext();
        int width = scroller.getWidth() - scroller.getPaddingLeft() - scroller.getPaddingRight();
        if (width <= 0)
            width = dp(context, CARD_WIDTH_DP);
        cards.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup rows = cards.findViewById(R.id.nearby_rows);
        int visible = 0;
        int extent = 0;
        if (rows != null) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                if (row.getVisibility() != View.VISIBLE)
                    continue;
                if (visible < VISIBLE_ROWS)
                    extent += row.getMeasuredHeight() + verticalMargins(row);
                visible++;
            }
        }
        visibleRows = visible;
        ViewGroup.LayoutParams params = scroller.getLayoutParams();
        if (params == null) return;
        if (heightFixed(view)) {
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                scroller.setLayoutParams(params);
            }
            return;
        }
        int target = extent <= 0 || visible <= VISIBLE_ROWS ? ViewGroup.LayoutParams.WRAP_CONTENT : extent + scroller.getPaddingTop() + scroller.getPaddingBottom();
        if (params.height != target) {
            params.height = target;
            scroller.setLayoutParams(params);
        }
    }

    private static int verticalMargins(View row) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            return margins.topMargin + margins.bottomMargin;
        }
        return 0;
    }

    private static boolean heightFixed(View view) {
        ViewGroup.LayoutParams params = contentRoot(view).getLayoutParams();
        return params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private static void handOffDrag(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN)
            EasyFloat.dragEnable(!shouldHandOffDrag(view, event), TAG);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            EasyFloat.dragEnable(true, TAG);
    }

    private static boolean shouldHandOffDrag(View view, MotionEvent event) {
        if (isInside(view.findViewById(R.id.overlay_resize_start), event) || isInside(view.findViewById(R.id.overlay_resize_end), event))
            return true;
        if (isInside(view.findViewById(R.id.overlay_opacity_seek), event))
            return true;
        ScrollView scroller = view.findViewById(R.id.overlay_scroll);
        return isInside(scroller, event) && scrollable(scroller);
    }

    private static boolean scrollable(ScrollView scroller) {
        if (scroller == null)
            return false;
        if (scroller.canScrollVertically(1) || scroller.canScrollVertically(-1))
            return true;
        View content = scroller.getChildCount() > 0 ? scroller.getChildAt(0) : null;
        if (content != null && content.getHeight() > 0 && scroller.getHeight() > 0)
            return content.getHeight() > scroller.getHeight();
        return visibleRows > VISIBLE_ROWS;
    }

    private static boolean isInside(View target, MotionEvent event) {
        if (target == null || target.getVisibility() != View.VISIBLE)
            return false;
        if (target.getWidth() <= 0 || target.getHeight() <= 0)
            return false;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x < location[0] + target.getWidth() && y >= location[1] && y < location[1] + target.getHeight();
    }

    static void onOverlayResized(View content) {
        NearbyListView cards = content.findViewById(R.id.overlay_cards);
        ScrollView scroller = content.findViewById(R.id.overlay_scroll);
        if (cards == null || scroller == null) return;
        fitCardsHeight(content, cards, scroller);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int windowWidth(Context context) {
        return dp(context, CARD_WIDTH_DP);
    }
}
