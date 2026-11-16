package com.rocom.nearby.map;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;

/**
 * The map section that lives inside {@code MainActivity}'s content container.
 *
 * <p>The card is declared in {@code main.xml} and never added or removed at
 * runtime; {@link #bind} attaches it to a scene once the tracker has published
 * a real player position, and the caller only changes its visibility.</p>
 *
 * <p>Resource points are not implemented; the only layer control is the
 * explored-area (染色) toggle.</p>
 */
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
        setPadding(padding, padding, padding, padding);
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

    /**
     * Attaches the card to a scene. Safe to call again when the scene changes;
     * the previous scene's bitmaps are released by {@link MapView#setScene}.
     */
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
            // First bind for this scene: start centred at 200m. Re-binding the
            // same scene must not throw away the user's pan/zoom.
            mapView.recenter();
        }
        applyPaintState();
    }

    /** True once {@link #bind} has supplied a scene. */
    public boolean isBound() {
        return scene != null;
    }

    @Nullable
    public MapCatalog.Scene getScene() {
        return scene;
    }

    /** Updates the player marker; the viewport follows it while following. */
    public void updateSelfPosition(double worldX, double worldY) {
        if (worldX == selfX && worldY == selfY) {
            return;
        }
        selfX = worldX;
        selfY = worldY;
        mapView.setSelf(worldX, worldY);
        mapView.invalidate();
    }

    /** True while the viewport tracks the player (locate button is blue). */
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

    public int getZoomIndex() {
        return mapView.getZoomIndex();
    }

    /** Restores the panned viewport after a configuration change. */
    public void restoreViewport(double centerX, double centerY, int zoomIndex) {
        mapView.restoreViewport(centerX, centerY, zoomIndex);
    }

    /**
     * Frees the map bitmaps. The card lives in the layout, so this is only
     * called when the hosting activity is going away for good.
     */
    public void release() {
        mapView.release();
    }

    private void togglePaint() {
        // The switch is process-wide: recording must keep working while the
        // map card is hidden and the user is in the game.
        MapPaintStore.setEnabled(!MapPaintStore.isEnabled());
        applyPaintState();
    }

    /** Mirrors the process-wide 染色 switch onto the chip and the map layer. */
    private void applyPaintState() {
        boolean visible = MapPaintStore.isEnabled();
        paintChip.setSelected(visible);
        paintChip.setBackgroundResource(visible
                ? R.drawable.bg_map_chip_on
                : R.drawable.bg_map_chip_off);
        paintChip.setTextColor(getContext().getColor(R.color.content_background));
        paintClear.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mapView.setPaintVisible(visible);
    }

    /** Blue while the viewport follows the player, black while it is panned. */
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
}
