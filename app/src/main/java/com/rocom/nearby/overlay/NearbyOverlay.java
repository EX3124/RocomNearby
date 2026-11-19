package com.rocom.nearby.overlay;

import android.app.Activity;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Toast;

import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.rocom.nearby.R;
import com.rocom.nearby.entity.NearbyListView;
import com.rocom.nearby.map.NearbyRuntime;

import java.lang.ref.WeakReference;

public final class NearbyOverlay {

    private static final String TAG = "nearby-overlay";
    private static WeakReference<Callback> callback = new WeakReference<>(null);
    private static boolean requesting;
    private static final String PREFS = "nearby";
    private static final String KEY_OPACITY = "opacity";
    private static final int MAX_OPACITY_PROGRESS = 100;
    private static final float MIN_ALPHA = 0.25f;
    private static final int CARD_WIDTH_DP = 264;
    private static final int VISIBLE_ROWS = 2;
    private static final int BOTTOM_INSET_DP = 12;
    private static WeakReference<View> floatView = new WeakReference<>(null);
    private static boolean hadCards;
    private static int visibleRows;
    private static final NearbyRuntime.Listener SNAPSHOT_LISTENER =
            NearbyOverlay::renderSnapshot;
    private static int nightMode;
    private static Context nightModeHost;

    private static final ComponentCallbacks NIGHT_MODE_CALLBACKS = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            if (newConfig == null) {
                return;
            }
            int mode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (mode == nightMode) {
                return;
            }
            nightMode = mode;
            dismiss();
        }

        @Override
        public void onLowMemory() {
        }
    };

    public interface Callback {
        void onOverlayStateChanged();
    }

    public static boolean isShowing() {
        return EasyFloat.isShow(TAG);
    }

    public static void show(Activity activity) {
        if (isShowing() || requesting) {
            View existing = isShowing() ? floatView.get() : null;
            if (existing != null) {
                keepInsideScreen(activity.getApplicationContext(), existing);
            }
            return;
        }
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
        if (root != null) {
            statusBarInset = root.getPaddingTop();
        }
        if (anchor != null) {
            anchor.getLocationOnScreen(anchorLocation);
            startX = Math.max(0, anchorLocation[0] + anchor.getWidth() - windowWidth(activity));
            startY = anchorLocation[1] + anchor.getHeight()
                    - statusBarInset + dp(activity, 2);
        }
        try {
            EasyFloat.with(activity)
                    .setLayout(R.layout.overlay, view -> bindView(appContext, view))
                    .setShowPattern(ShowPattern.ALL_TIME)
                    .setLocation(startX, Math.max(0, startY))
                    .setTag(TAG)
                    .setDragEnable(true)
                    .setDisplayHeight(OverlayScreen::longSide)
                    .registerCallbacks(new OnFloatCallbacks() {
                        @Override
                        public void createdResult(boolean isCreated, String msg, View view) {
                            requesting = false;
                            notifyState();
                        }

                        @Override
                        public void show(View view) {
                            attach(view);
                            notifyState();
                        }

                        @Override
                        public void hide(View view) {
                            detach();
                            notifyState();
                        }

                        @Override
                        public void dismiss() {
                            detach();
                            notifyState();
                        }

                        @Override
                        public void touchEvent(View view, MotionEvent event) {
                            handOffDrag(view, event);
                        }

                        @Override
                        public void drag(View view, MotionEvent event) {
                            keepInsideScreen(view.getContext().getApplicationContext(), view);
                        }

                        @Override
                        public void dragEnd(View view) {
                            keepInsideScreen(view.getContext().getApplicationContext(), view);
                        }
                    })
                    .show();
        } catch (Throwable e) {
            requesting = false;
            notifyState();
        }
    }

    public static void dismiss() {
        requesting = false;
        detach();
        EasyFloat.dismiss(TAG, true);
        notifyState();
    }

    private static void bindView(Context appContext, View view) {
        View close = view.findViewById(R.id.overlay_close);
        if (close != null) {
            close.setOnClickListener(clicked -> clicked.post(NearbyOverlay::dismiss));
        }
        bindOpacity(appContext, view);
        NearbyListView cards = view.findViewById(R.id.overlay_cards);
        if (cards != null) {
            cards.setAlertPublishing(false);
            cards.setHeadFlush(true);
            cards.setBackground(null);
        }
        View scroller = view.findViewById(R.id.overlay_scroll);
        if (scroller != null) {
            scroller.setPadding(0, 0, 0, dp(appContext, BOTTOM_INSET_DP));
        }
        bindResizeGrips(view);
        attach(view);
    }

    private static void bindResizeGrips(View view) {
        View body = contentRoot(view);
        OverlayResizeGrip.MinSize minSize = new OverlayResizeGrip.MinSize();
        minSize.capture(body);
        OverlayResizeGrip start = view.findViewById(R.id.overlay_resize_start);
        if (start != null) {
            start.bind(body, TAG, OverlayResizeGrip.CORNER_START, minSize);
        }
        OverlayResizeGrip end = view.findViewById(R.id.overlay_resize_end);
        if (end != null) {
            end.bind(body, TAG, OverlayResizeGrip.CORNER_END, minSize);
        }
    }

    private static View contentRoot(View view) {
        View body = view.findViewById(R.id.overlay_body);
        return body != null ? body : view;
    }

    private static void bindOpacity(Context appContext, View view) {
        SeekBar seek = view.findViewById(R.id.overlay_opacity_seek);
        View icon = view.findViewById(R.id.overlay_opacity_icon);
        if (seek == null) {
            return;
        }
        seek.setVisibility(View.GONE);
        final SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        seek.setMax(MAX_OPACITY_PROGRESS);
        seek.setProgress(clampProgress(prefs.getInt(KEY_OPACITY, MAX_OPACITY_PROGRESS)));
        applyOpacity(view, seek.getProgress());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                applyOpacity(view, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(KEY_OPACITY, clampProgress(bar.getProgress())).apply();
            }
        });
        if (icon != null) {
            icon.setOnClickListener(clicked -> seek.setVisibility(
                    seek.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        }
    }

    private static void applyOpacity(View body, int progress) {
        float ratio = clampProgress(progress) / (float) MAX_OPACITY_PROGRESS;
        body.setAlpha(MIN_ALPHA + (1f - MIN_ALPHA) * ratio);
    }

    private static int clampProgress(int progress) {
        return Math.max(0, Math.min(MAX_OPACITY_PROGRESS, progress));
    }

    private static void attach(View view) {
        floatView = new WeakReference<>(view);
        watchNightMode(view.getContext());
        NearbyRuntime.addListener(SNAPSHOT_LISTENER);
        renderSnapshot(NearbyRuntime.current());
    }

    private static void detach() {
        unwatchNightMode();
        NearbyRuntime.removeListener(SNAPSHOT_LISTENER);
        floatView = new WeakReference<>(null);
        hadCards = false;
        visibleRows = 0;
    }

    private static void watchNightMode(Context context) {
        Context host = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        unwatchNightMode();
        nightMode = host.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        host.registerComponentCallbacks(NIGHT_MODE_CALLBACKS);
        nightModeHost = host;
    }

    private static void unwatchNightMode() {
        if (nightModeHost == null) {
            return;
        }
        nightModeHost.unregisterComponentCallbacks(NIGHT_MODE_CALLBACKS);
        nightModeHost = null;
    }

    private static void renderSnapshot(NearbyRuntime.Snapshot snapshot) {
        View view = floatView.get();
        if (view == null) {
            return;
        }
        NearbyListView cards = view.findViewById(R.id.overlay_cards);
        ScrollView scroller = view.findViewById(R.id.overlay_scroll);
        View empty = view.findViewById(R.id.overlay_empty);
        if (cards == null || scroller == null) {
            return;
        }
        cards.bind(snapshot.entities, Double.NaN, Double.NaN);
        boolean hasCards = cards.getVisibility() == View.VISIBLE;
        if (empty != null) {
            empty.setVisibility(hasCards ? View.GONE : View.VISIBLE);
        }
        scroller.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        if (hasCards) {
            if (!hadCards) {
                scroller.scrollTo(0, 0);
            }
            fitCardsHeight(view, cards, scroller);
        } else {
            visibleRows = 0;
        }
        hadCards = hasCards;
    }

    private static void fitCardsHeight(View view, NearbyListView cards, ScrollView scroller) {
        Context context = view.getContext();
        int width = scroller.getWidth()
                - scroller.getPaddingLeft() - scroller.getPaddingRight();
        if (width <= 0) {
            width = dp(context, CARD_WIDTH_DP);
        }
        cards.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup rows = cards.findViewById(R.id.nearby_rows);
        int visible = 0;
        int extent = 0;
        if (rows != null) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                if (row.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (visible < VISIBLE_ROWS) {
                    extent += row.getMeasuredHeight() + verticalMargins(row);
                }
                visible++;
            }
        }
        visibleRows = visible;
        ViewGroup.LayoutParams params = scroller.getLayoutParams();
        if (params == null) {
            return;
        }
        if (heightFixed(view)) {
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                scroller.setLayoutParams(params);
            }
            return;
        }
        int target = extent <= 0 || visible <= VISIBLE_ROWS
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : extent + scroller.getPaddingTop() + scroller.getPaddingBottom();
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
        if (action == MotionEvent.ACTION_DOWN) {
            EasyFloat.dragEnable(!shouldHandOffDrag(view, event), TAG);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            EasyFloat.dragEnable(true, TAG);
        }
    }

    private static boolean shouldHandOffDrag(View view, MotionEvent event) {
        if (isInside(view.findViewById(R.id.overlay_resize_start), event)
                || isInside(view.findViewById(R.id.overlay_resize_end), event)) {
            return true;
        }
        if (isInside(view.findViewById(R.id.overlay_opacity_seek), event)) {
            return true;
        }
        ScrollView scroller = view.findViewById(R.id.overlay_scroll);
        return isInside(scroller, event) && scrollable(scroller);
    }

    private static boolean scrollable(ScrollView scroller) {
        if (scroller == null) {
            return false;
        }
        if (scroller.canScrollVertically(1) || scroller.canScrollVertically(-1)) {
            return true;
        }
        View content = scroller.getChildCount() > 0 ? scroller.getChildAt(0) : null;
        if (content != null && content.getHeight() > 0 && scroller.getHeight() > 0) {
            return content.getHeight() > scroller.getHeight();
        }
        return visibleRows > VISIBLE_ROWS;
    }

    private static boolean isInside(View target, MotionEvent event) {
        if (target == null || target.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x < location[0] + target.getWidth()
                && y >= location[1] && y < location[1] + target.getHeight();
    }

    static void onOverlayResized(View content) {
        NearbyListView cards = content.findViewById(R.id.overlay_cards);
        ScrollView scroller = content.findViewById(R.id.overlay_scroll);
        if (cards == null || scroller == null) {
            return;
        }
        fitCardsHeight(content, cards, scroller);
    }

    private static void keepInsideScreen(Context context, View floatRoot) {
        if (context == null || floatRoot == null) {
            return;
        }
        WindowManager.LayoutParams params = windowParams(floatRoot);
        int width = floatRoot.getWidth();
        int height = floatRoot.getHeight();
        if (params == null || width <= 0 || height <= 0) {
            return;
        }
        Rect screen = OverlayScreen.bounds(context);
        int maxX = Math.max(0, screen.width() - width);
        int maxY = Math.max(0, screen.height() - height);
        int x = Math.min(Math.max(params.x, 0), maxX);
        int y = Math.min(Math.max(params.y, 0), maxY);
        if (x != params.x || y != params.y) {
            EasyFloat.updateFloat(TAG, x, y);
        }
    }

    private static WindowManager.LayoutParams windowParams(View view) {
        View root = view;
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

    private static void notifyState() {
        Callback target = callback.get();
        if (target != null) {
            target.onOverlayStateChanged();
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    
    private static int windowWidth(Context context) {
        return dp(context, CARD_WIDTH_DP);
    }
}
