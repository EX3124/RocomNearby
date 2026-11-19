package com.rocom.nearby.map;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;
import com.rocom.nearby.entity.NearbyEntity;

import java.util.List;

public class MapCardView extends LinearLayout {

    private final MapView mapView;
    private final TextView zoomOut;
    private final TextView zoomIn;
    private final TextView zoomLevel;
    private final TextView paintChip;
    private final TextView paintClear;
    private final TextView locate;
    @Nullable
    private MapCatalog.Scene scene;
    @Nullable
    private MapPaint paint;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;
    @Nullable
    private Runnable followChangedListener;

    public MapCardView(Context context) {
        this(context, null);
    }

    public MapCardView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MapCardView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_content);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setPadding(padding, padding, padding, landscape ? padding / 2 : padding);
        LayoutInflater.from(context).inflate(R.layout.map, this, true);
        mapView = findViewById(R.id.map_view);
        zoomOut = findViewById(R.id.zoom_out);
        zoomIn = findViewById(R.id.zoom_in);
        zoomLevel = findViewById(R.id.zoom_level);
        paintChip = findViewById(R.id.paint_chip);
        paintClear = findViewById(R.id.paint_clear);
        locate = findViewById(R.id.locate);
        mapView.setOnViewportChangedListener((label, canZoomIn, canZoomOut) -> {
            zoomLevel.setText(label);
            updateZoomButton(zoomIn, canZoomIn);
            updateZoomButton(zoomOut, canZoomOut);
        });
        mapView.setOnFollowChangedListener(this::onFollowChanged);
        zoomOut.setOnClickListener(view -> mapView.zoomOut());
        zoomIn.setOnClickListener(view -> mapView.zoomIn());
        locate.setOnClickListener(view -> mapView.toggleFollowing());
        paintChip.setOnClickListener(view -> togglePaint());
        paintClear.setOnClickListener(view -> {
            if (paint != null) {
                paint.reset();
                mapView.invalidate();
            }
        });
        applyPaintState();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (landscape && heightMode == MeasureSpec.EXACTLY) {
            if (measureHeightDriven(widthMeasureSpec, heightMeasureSpec)) {
                return;
            }
        } else if (!landscape && heightMode != MeasureSpec.EXACTLY) {
            if (measureWidthDriven(widthMeasureSpec, heightMeasureSpec)) {
                return;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private boolean measureHeightDriven(int widthMeasureSpec, int heightMeasureSpec) {
        int totalHeight = MeasureSpec.getSize(heightMeasureSpec);
        int controlsHeight = controlsHeight();
        int availableForMap = totalHeight - getPaddingTop() - getPaddingBottom()
                - controlsHeight;
        if (availableForMap <= 0) {
            return false;
        }
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int maxSize = (widthMode == MeasureSpec.UNSPECIFIED)
                ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec);
        Sizing size = Sizing.landscapeSize(
                availableForMap, maxSize,
                getPaddingLeft() + getPaddingRight(), landscapeWidthBudget());
        if (size == null) {
            return false;
        }
        measureMapAndControls(size, controlsHeight);
        int desiredWidth = size.width + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), totalHeight);
        return true;
    }

    private boolean measureWidthDriven(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            return false;
        }
        int controlsHeight = controlsHeight();
        Sizing size = Sizing.portraitSize(
                MeasureSpec.getSize(widthMeasureSpec),
                getPaddingLeft() + getPaddingRight(),
                getPaddingTop() + getPaddingBottom(),
                controlsHeight,
                cardHeightLimit(heightMeasureSpec));
        if (size == null) {
            return false;
        }
        measureMapAndControls(size, controlsHeight);
        View controls = findViewById(R.id.map_controls);
        int measuredControls = (controls != null) ? controls.getMeasuredHeight() : 0;
        int desiredWidth = size.width + getPaddingLeft() + getPaddingRight();
        int desiredHeight = size.height + getPaddingTop() + getPaddingBottom()
                + measuredControls;
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
        return true;
    }

    private void measureMapAndControls(Sizing size, int controlsHeight) {
        View map = findViewById(R.id.map_view);
        if (map != null) {
            map.measure(
                    MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY));
        }
        View controls = findViewById(R.id.map_controls);
        if (controls != null) {
            int controlsHeightSpec = (controlsHeight > 0)
                    ? MeasureSpec.makeMeasureSpec(controlsHeight, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            controls.measure(
                    MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
                    controlsHeightSpec);
        }
    }

    private int controlsHeight() {
        View controls = findViewById(R.id.map_controls);
        if (controls != null && controls.getLayoutParams() != null) {
            return Math.max(0, controls.getLayoutParams().height);
        }
        return 0;
    }

    private int cardHeightLimit(int heightMeasureSpec) {
        int limit = Sizing.screenHeightBudget(getResources().getDisplayMetrics().heightPixels);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            int parentLimit = MeasureSpec.getSize(heightMeasureSpec);
            limit = (limit > 0) ? Math.min(limit, parentLimit) : parentLimit;
        }
        return limit;
    }

    private int landscapeWidthBudget() {
        int screenWidth = 0;
        ViewParent parent = getParent();
        if (parent instanceof View) {
            screenWidth = ((View) parent).getWidth();
        }
        if (screenWidth <= 0) {
            screenWidth = getResources().getDisplayMetrics().widthPixels;
        }
        int budget = Sizing.screenWidthBudget(screenWidth);
        if (budget <= 0) {
            return 0;
        }
        int margins = 0;
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
            margins = marginParams.leftMargin + marginParams.rightMargin;
        }
        return budget - margins;
    }

    public void bind(MapCatalog.Scene newScene, @Nullable MapPaint newPaint,
                     double worldX, double worldY) {
        boolean sameScene = scene != null && scene.sceneId == newScene.sceneId;
        scene = newScene;
        paint = newPaint;
        selfX = worldX;
        selfY = worldY;
        mapView.setScene(newScene);
        mapView.setPaint(paint);
        mapView.setSelf(worldX, worldY);
        if (!sameScene) {
            mapView.recenter();
        }
        applyPaintState();
    }

    public boolean isBound() {
        return scene != null;
    }

    @Nullable
    public MapCatalog.Scene getScene() {
        return scene;
    }

    public void setEntities(@Nullable List<NearbyEntity> entities) {
        mapView.setEntities(entities);
    }

    public void trackEntity(@Nullable NearbyEntity entity) {
        mapView.trackEntity(entity);
    }

    public void updateSelfPosition(double worldX, double worldY) {
        if (worldX == selfX && worldY == selfY) {
            return;
        }
        selfX = worldX;
        selfY = worldY;
        mapView.setSelf(worldX, worldY);
        mapView.invalidate();
    }

    public boolean isFollowing() {
        return mapView.isFollowing();
    }

    public void setFollowing(boolean following) {
        mapView.setFollowing(following);
    }

    public void setOnFollowChangedListener(@Nullable Runnable listener) {
        followChangedListener = listener;
    }

    public double getCenterX() {
        return mapView.getCenterX();
    }

    public double getCenterY() {
        return mapView.getCenterY();
    }

    public double getSpanMetres() {
        return mapView.getSpanMetres();
    }

    public void restoreViewport(double centerX, double centerY, double spanMetres) {
        mapView.restoreViewport(centerX, centerY, spanMetres);
    }

    public void release() {
        mapView.release();
    }

    private void togglePaint() {
        MapPaintStore.setEnabled(!MapPaintStore.isEnabled());
        applyPaintState();
    }

    private void applyPaintState() {
        boolean visible = MapPaintStore.isEnabled();
        paintChip.setSelected(visible);
        paintChip.setBackgroundResource(visible
                ? R.drawable.bg_map_chip_on
                : R.drawable.bg_map_chip_off);
        paintChip.setTextColor(getContext().getColor(visible
                ? R.color.content_background
                : R.color.paint_button_text));
        paintClear.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mapView.setPaintVisible(visible);
    }

    private void onFollowChanged(boolean following) {
        locate.setTextColor(getContext().getColor(following
                ? R.color.action_button_gradient_start
                : R.color.text));
        if (followChangedListener != null) {
            followChangedListener.run();
        }
    }

    private static void updateZoomButton(TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    static final class Sizing {

        static final float MAP_ASPECT = 4f / 3f;
        static final float MAP_CARD_MAX_WIDTH_FRACTION = 0.56f;
        static final float MAP_CARD_MAX_HEIGHT_FRACTION = 0.7f;
        final int width;
        final int height;
        final boolean fourThreeKept;

        private Sizing(int width, int height, boolean fourThreeKept) {
            this.width = width;
            this.height = height;
            this.fourThreeKept = fourThreeKept;
        }

        static Sizing landscapeSize(int availableForMapHeight, int outerWidthLimit,
                int horizontalPadding, int screenWidthBudget) {
            if (availableForMapHeight <= 0 || horizontalPadding < 0) {
                return null;
            }
            int maxMapWidth = (outerWidthLimit == Integer.MAX_VALUE)
                    ? Integer.MAX_VALUE
                    : outerWidthLimit - horizontalPadding;
            if (maxMapWidth <= 0) {
                return null;
            }
            int idealMapWidth = Math.round(availableForMapHeight * MAP_ASPECT);
            if (screenWidthBudget > 0 && idealMapWidth + horizontalPadding > screenWidthBudget) {
                int outerLimit = Math.min(outerWidthLimit, screenWidthBudget);
                int width = outerLimit - horizontalPadding;
                if (width <= 0) {
                    return null;
                }
                return new Sizing(width, availableForMapHeight, false);
            }
            if (idealMapWidth <= maxMapWidth) {
                return new Sizing(idealMapWidth, availableForMapHeight, true);
            }
            return new Sizing(maxMapWidth, Math.round(maxMapWidth / MAP_ASPECT), true);
        }

        static int screenWidthBudget(int screenWidth) {
            if (screenWidth <= 0) {
                return 0;
            }
            return Math.round(screenWidth * MAP_CARD_MAX_WIDTH_FRACTION);
        }

        static int screenHeightBudget(int screenHeight) {
            if (screenHeight <= 0) {
                return 0;
            }
            return Math.round(screenHeight * MAP_CARD_MAX_HEIGHT_FRACTION);
        }

        static Sizing portraitSize(int outerWidth, int horizontalPadding,
                int verticalPadding, int controlsHeight, int cardHeightLimit) {
            if (outerWidth <= 0 || horizontalPadding < 0 || verticalPadding < 0
                    || controlsHeight < 0) {
                return null;
            }
            int mapWidth = outerWidth - horizontalPadding;
            if (mapWidth <= 0) {
                return null;
            }
            int idealHeight = Math.round(mapWidth * 4f / 3f);
            int maxHeight = Integer.MAX_VALUE;
            if (cardHeightLimit > 0) {
                maxHeight = cardHeightLimit - verticalPadding - controlsHeight;
                if (maxHeight <= 0) {
                    return null;
                }
            }
            if (idealHeight <= maxHeight) {
                return new Sizing(mapWidth, idealHeight, true);
            }
            return new Sizing(mapWidth, maxHeight, false);
        }
    }
}
