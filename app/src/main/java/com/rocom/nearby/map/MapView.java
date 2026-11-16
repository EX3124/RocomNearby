package com.rocom.nearby.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pannable / zoomable raster map for a {@link MapCatalog.Scene}.
 *
 * <p>The base image is 4096x4096, so it is never decoded as a whole while the
 * region decoder is available: zoomed-in levels decode only the visible source
 * rectangle (plus a margin), while zoomed-out levels use a 1024px overview.
 * If region decoding is not supported for the bundled WebP, the view falls
 * back to a single RGB_565 bitmap (or a half-size one on low-memory devices).
 * That keeps the default 200m view as sharp as the original map.</p>
 */
public class MapView extends View {
    public interface OnViewportChangedListener {
        void onViewportChanged(String zoomLabel, boolean canZoomIn, boolean canZoomOut);
    }

    /** Follow mode: the viewport tracks the player until the user pans away. */
    public interface OnFollowChangedListener {
        void onFollowChanged(boolean following);
    }

    /** Zoom span in metres, matching the reference map control. */
    private static final double[] ZOOM_SPANS = {100, 200, 400, 800, 1500, 3000};
    private static final int DEFAULT_ZOOM_INDEX = 1;

    /** Above this span the 1024px overview is sharp enough and much cheaper. */
    private static final double MAX_REGION_SPAN_M = 800.0;
    private static final long MAX_REGION_PIXELS = 4_000_000L;

    private static final int PAINT_FILL = Color.argb(82, 168, 85, 247);
    private static final int PAINT_EDGE = Color.argb(235, 233, 213, 255);
    private static final int TOO_MANY_CELLS = 6000;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratchRect = new RectF();
    private final Rect regionSrc = new Rect();

    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor();
    private final int touchSlop;

    @Nullable
    private MapCatalog.Scene scene;
    @Nullable
    private MapPaint paint;
    @Nullable
    private OnViewportChangedListener viewportListener;
    @Nullable
    private OnFollowChangedListener followListener;

    @Nullable
    private Bitmap overview;
    @Nullable
    private Bitmap regionBitmap;
    @Nullable
    private Bitmap fullBitmap;
    @Nullable
    private Bitmap paintOverlay;
    @Nullable
    private BitmapRegionDecoder regionDecoder;

    private int zoomIndex = DEFAULT_ZOOM_INDEX;
    private double centerX;
    private double centerY;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;

    private boolean paintVisible;
    private boolean following = true;
    private int paintOverlayVersion = -1;
    private boolean initialized;
    private boolean regionRequestPending;
    private boolean fullBitmapRequested;
    private boolean released;
    private int decodeGeneration;
    private int requestedRegionKey;

    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    private float lastTouchX;
    private float lastTouchY;
    private float downTouchX;
    private float downTouchY;
    private boolean dragging;

    public MapView(Context context) {
        this(context, null);
    }

    public MapView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MapView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(context.getColor(R.color.content_background));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(PAINT_FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setColor(PAINT_EDGE);
        edgePaint.setStrokeWidth(dp(1.6f));
        markerFillPaint.setStyle(Paint.Style.FILL);
        markerFillPaint.setColor(Color.WHITE);
        markerRingPaint.setStyle(Paint.Style.STROKE);
        markerRingPaint.setStrokeWidth(dp(2.5f));
        markerRingPaint.setColor(Color.argb(235, 12, 20, 33));
        markerGlowPaint.setStyle(Paint.Style.FILL);
        markerGlowPaint.setColor(Color.argb(64, 255, 255, 255));

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int height;
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            // The reference map viewport is a 3:4 portrait rectangle. Landscape
            // layout work is deliberately deferred; this keeps the simple
            // portrait ratio everywhere for now.
            height = Math.round(width * 4f / 3f);
        }
        setMeasuredDimension(width, height);
    }

    public void setScene(@Nullable MapCatalog.Scene newScene) {
        if (scene == newScene) {
            return;
        }
        scene = newScene;
        releaseImages();
        initialized = false;
        regionRequestPending = false;
        fullBitmapRequested = false;
        paintOverlayVersion = -1;
        if (newScene == null) {
            invalidate();
            return;
        }
        centerX = newScene.defaultFocusX;
        centerY = newScene.defaultFocusY;
        selfX = newScene.defaultFocusX;
        selfY = newScene.defaultFocusY;
        zoomIndex = DEFAULT_ZOOM_INDEX;
        requestLayout();
        invalidate();
        ensureInitialized();
        notifyViewport();
    }

    public void setPaint(@Nullable MapPaint mapPaint) {
        paint = mapPaint;
        paintOverlay = null;
        paintOverlayVersion = -1;
        invalidate();
    }

    public void setPaintVisible(boolean visible) {
        if (paintVisible == visible) {
            return;
        }
        paintVisible = visible;
        invalidate();
    }

    public boolean isPaintVisible() {
        return paintVisible;
    }

    public void setOnViewportChangedListener(@Nullable OnViewportChangedListener listener) {
        viewportListener = listener;
        notifyViewport();
    }

    public void setOnFollowChangedListener(@Nullable OnFollowChangedListener listener) {
        followListener = listener;
        notifyFollow();
    }

    public boolean isFollowing() {
        return following;
    }

    /**
     * Turns player-following on/off. Enabling it immediately pans back to the
     * player so the marker is visible again; the zoom level is kept.
     */
    public void setFollowing(boolean follow) {
        if (following == follow) {
            return;
        }
        following = follow;
        if (following) {
            panToSelf();
            invalidate();
        }
        notifyFollow();
    }

    /**
     * Locate button: toggles player-following. Turning it on pans back to the
     * player without changing the current zoom level.
     */
    public void toggleFollowing() {
        setFollowing(!following);
    }

    /** Sets the player marker, in world centimetres. */
    public void setSelf(double worldX, double worldY) {
        selfX = worldX;
        selfY = worldY;
        if (following) {
            centerX = worldX;
            centerY = worldY;
        }
        invalidate();
    }

    public double getSelfX() {
        return selfX;
    }

    public double getSelfY() {
        return selfY;
    }

    public double getSpanMetres() {
        double span = ZOOM_SPANS[zoomIndex];
        return Double.isFinite(span) ? span : 200;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public int getZoomIndex() {
        return zoomIndex;
    }

    /**
     * Restores a previously saved viewport without touching follow mode, so a
     * configuration change keeps both the pan and the locate-button state.
     */
    public void restoreViewport(double worldCenterX, double worldCenterY, int index) {
        if (!Double.isFinite(worldCenterX) || !Double.isFinite(worldCenterY)) {
            return;
        }
        centerX = worldCenterX;
        centerY = worldCenterY;
        zoomIndex = Math.max(0, Math.min(ZOOM_SPANS.length - 1, index));
        invalidate();
        notifyViewport();
    }

    public String getZoomLabel() {
        return formatSpan(getSpanMetres());
    }

    public boolean canZoomIn() {
        return zoomIndex > 0;
    }

    public boolean canZoomOut() {
        return zoomIndex < ZOOM_SPANS.length - 1;
    }

    public void zoomIn() {
        setZoomIndex(zoomIndex - 1);
    }

    public void zoomOut() {
        setZoomIndex(zoomIndex + 1);
    }

    private void setZoomIndex(int index) {
        int clamped = Math.max(0, Math.min(ZOOM_SPANS.length - 1, index));
        if (clamped == zoomIndex) {
            return;
        }
        zoomIndex = clamped;
        invalidate();
        notifyViewport();
    }

    /** Resets zoom and pan so the self marker is in the middle again. */
    public void recenter() {
        if (scene == null) {
            return;
        }
        boolean followChanged = !following;
        following = true;
        panToSelf();
        zoomIndex = DEFAULT_ZOOM_INDEX;
        invalidate();
        notifyViewport();
        if (followChanged) {
            notifyFollow();
        }
    }

    private void panToSelf() {
        if (scene == null) {
            return;
        }
        centerX = Double.isFinite(selfX) ? selfX : scene.defaultFocusX;
        centerY = Double.isFinite(selfY) ? selfY : scene.defaultFocusY;
    }

    public void release() {
        released = true;
        decoderExecutor.shutdownNow();
        releaseImages();
    }

    private void releaseImages() {
        if (regionBitmap != null && regionBitmap != fullBitmap) {
            regionBitmap.recycle();
        }
        if (overview != null) {
            overview.recycle();
        }
        if (fullBitmap != null) {
            fullBitmap.recycle();
        }
        if (paintOverlay != null) {
            paintOverlay.recycle();
        }
        regionBitmap = null;
        overview = null;
        fullBitmap = null;
        paintOverlay = null;
        regionDecoder = null;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        ensureInitialized();
        invalidate();
    }

    private void ensureInitialized() {
        if (initialized || scene == null || released) {
            return;
        }
        if (getWidth() <= 0) {
            post(this::ensureInitialized);
            return;
        }
        initialized = true;
        final MapCatalog.Scene target = scene;
        decoderExecutor.execute(() -> loadInitialBitmaps(target));
    }

    private void loadInitialBitmaps(MapCatalog.Scene target) {
        Bitmap loadedOverview = decodeAsset(target.assetPath, 4);
        BitmapRegionDecoder decoder = null;
        if (!released) {
            decoder = openRegionDecoder(target.assetPath);
        }
        if (released) {
            recycleQuietly(loadedOverview);
            return;
        }
        final Bitmap overviewResult = loadedOverview;
        final BitmapRegionDecoder decoderResult = decoder;
        post(() -> {
            if (released || scene != target) {
                recycleQuietly(overviewResult);
                return;
            }
            overview = overviewResult;
            regionDecoder = decoderResult;
            if (decoderResult == null) {
                // Without a region decoder the only way to keep zoomed-in
                // levels sharp is one full bitmap; decode it lazily below.
                requestFullBitmap();
            }
            invalidate();
        });
    }

    @Nullable
    private Bitmap decodeAsset(String path, int sampleSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inSampleSize = sampleSize;
        options.inScaled = false;
        try (InputStream stream = getContext().getAssets().open(path)) {
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private BitmapRegionDecoder openRegionDecoder(String path) {
        try (InputStream stream = getContext().getAssets().open(path)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 21);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            byte[] data = buffer.toByteArray();
            return BitmapRegionDecoder.newInstance(data, 0, data.length, false);
        } catch (Throwable e) {
            return null;
        }
    }

    private void requestFullBitmap() {
        if (fullBitmapRequested || fullBitmap != null || scene == null || released) {
            return;
        }
        fullBitmapRequested = true;
        final MapCatalog.Scene target = scene;
        decoderExecutor.execute(() -> {
            Bitmap bitmap = decodeAsset(target.assetPath, 1);
            if (bitmap == null) {
                bitmap = decodeAsset(target.assetPath, 2);
            }
            Bitmap result = bitmap;
            post(() -> {
                if (released || scene != target) {
                    recycleQuietly(result);
                    return;
                }
                fullBitmap = result;
                invalidate();
            });
        });
    }

    private void requestRegionRendering() {
        if (scene == null || released || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (getSpanMetres() > MAX_REGION_SPAN_M) {
            // Overview is good enough here.
            return;
        }
        if (regionDecoder == null) {
            return;
        }
        final double metresPerPixel = scene.metresPerPixel();
        final double sourceWidth = getSpanMetres() / metresPerPixel;
        final float currentScale = (float) (getWidth() / sourceWidth);
        final double sourceHeight = getHeight() / currentScale;
        Rect needed = new Rect();
        Rect buffered = new Rect();
        computeSourceRect(sourceWidth, sourceHeight, needed, buffered);
        if (regionBitmap != null && regionSrc.contains(needed)) {
            return;
        }
        int key = (buffered.left << 24) ^ (buffered.top << 12) ^ buffered.right ^ (buffered.bottom << 4);
        if (regionRequestPending && key == requestedRegionKey) {
            return;
        }
        requestedRegionKey = key;
        regionRequestPending = true;
        final int generation = ++decodeGeneration;
        final Rect request = new Rect(buffered);
        int sample = 1;
        while ((long) (request.width() / sample) * (request.height() / sample)
                > MAX_REGION_PIXELS) {
            sample *= 2;
        }
        final int requestSample = sample;
        final BitmapRegionDecoder decoder = regionDecoder;
        decoderExecutor.execute(() -> {
            Bitmap decoded = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = requestSample;
                options.inScaled = false;
                decoded = decoder.decodeRegion(request, options);
            } catch (Throwable ignored) {
                decoded = null;
            }
            final Bitmap result = decoded;
            post(() -> {
                if (generation != decodeGeneration) {
                    // A newer viewport request superseded this one; keep its
                    // pending flag intact.
                    recycleQuietly(result);
                    return;
                }
                regionRequestPending = false;
                if (released || scene == null) {
                    recycleQuietly(result);
                    return;
                }
                if (result == null) {
                    // Region decoding failed (some devices do not support
                    // WebP subsets); fall back to one full image.
                    regionDecoder = null;
                    requestFullBitmap();
                    invalidate();
                    return;
                }
                if (regionBitmap != null && regionBitmap != fullBitmap) {
                    regionBitmap.recycle();
                }
                regionBitmap = result;
                regionSrc.set(request);
                invalidate();
            });
        });
    }

    private void computeSourceRect(double sourceWidth, double sourceHeight,
                                   Rect visible, Rect buffered) {
        if (scene == null) {
            return;
        }
        double centerSourceX = scene.imageX(centerX);
        double centerSourceY = scene.imageY(centerY);
        double visibleLeft = centerSourceX - sourceWidth / 2.0;
        double visibleTop = centerSourceY - sourceHeight / 2.0;
        clampRect(visible, visibleLeft, visibleTop, sourceWidth, sourceHeight);
        double marginX = sourceWidth * 0.35;
        double marginY = sourceHeight * 0.35;
        clampRect(buffered, visibleLeft - marginX, visibleTop - marginY,
                sourceWidth + marginX * 2, sourceHeight + marginY * 2);
    }

    private void clampRect(Rect out, double left, double top, double width, double height) {
        if (scene == null) {
            out.setEmpty();
            return;
        }
        int l = (int) Math.floor(Math.max(0, left));
        int t = (int) Math.floor(Math.max(0, top));
        int r = (int) Math.ceil(Math.min(scene.imageWidth, left + width));
        int b = (int) Math.ceil(Math.min(scene.imageHeight, top + height));
        if (r <= l) {
            r = Math.min(scene.imageWidth, l + 1);
        }
        if (b <= t) {
            b = Math.min(scene.imageHeight, t + 1);
        }
        out.set(l, t, r, b);
    }

    private void updateTransform() {
        if (scene == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double sourceWidth = getSpanMetres() / scene.metresPerPixel();
        double centreSourceX = scene.imageX(centerX);
        double centreSourceY = scene.imageY(centerY);
        double halfWidth = sourceWidth / 2.0;
        double sourceHeight = getHeight() * sourceWidth / getWidth();
        double halfHeight = sourceHeight / 2.0;

        if (halfWidth * 2 >= scene.imageWidth) {
            centreSourceX = scene.imageWidth / 2.0;
        } else {
            centreSourceX = Math.max(halfWidth, Math.min(scene.imageWidth - halfWidth, centreSourceX));
        }
        if (halfHeight * 2 >= scene.imageHeight) {
            centreSourceY = scene.imageHeight / 2.0;
        } else {
            centreSourceY = Math.max(halfHeight,
                    Math.min(scene.imageHeight - halfHeight, centreSourceY));
        }
        centerX = scene.worldX(centreSourceX);
        centerY = scene.worldY(centreSourceY);

        scale = (float) (getWidth() / sourceWidth);
        offsetX = (float) (getWidth() / 2.0 - centreSourceX * scale);
        offsetY = (float) (getHeight() / 2.0 - centreSourceY * scale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scene == null) {
            return;
        }
        updateTransform();
        if (overview != null) {
            scratchRect.set(offsetX, offsetY,
                    offsetX + scene.imageWidth * scale,
                    offsetY + scene.imageHeight * scale);
            canvas.drawBitmap(overview, null, scratchRect, bitmapPaint);
        }
        if (regionBitmap != null && !regionSrc.isEmpty()
                && getSpanMetres() <= MAX_REGION_SPAN_M) {
            scratchRect.set(offsetX + regionSrc.left * scale,
                    offsetY + regionSrc.top * scale,
                    offsetX + regionSrc.right * scale,
                    offsetY + regionSrc.bottom * scale);
            canvas.drawBitmap(regionBitmap, null, scratchRect, bitmapPaint);
        } else if (fullBitmap != null) {
            scratchRect.set(offsetX, offsetY,
                    offsetX + scene.imageWidth * scale,
                    offsetY + scene.imageHeight * scale);
            canvas.drawBitmap(fullBitmap, null, scratchRect, bitmapPaint);
        }
        if (paintVisible && paint != null) {
            drawPaint(canvas, scene);
        }
        drawSelfMarker(canvas, scene);
        if (scene != null) {
            requestRegionRendering();
        }
    }

    private void drawPaint(Canvas canvas, MapCatalog.Scene mapScene) {
        int grid = paint.getGridSize();
        if (grid <= 0) {
            return;
        }
        double sourceLeft = Math.max(0, -offsetX / scale);
        double sourceTop = Math.max(0, -offsetY / scale);
        double sourceRight = Math.min(mapScene.imageWidth, (getWidth() - offsetX) / scale);
        double sourceBottom = Math.min(mapScene.imageHeight, (getHeight() - offsetY) / scale);
        if (sourceRight <= sourceLeft || sourceBottom <= sourceTop) {
            return;
        }
        int fromX = Math.max(0, (int) Math.floor(sourceLeft / mapScene.imageWidth * grid));
        int toX = Math.min(grid - 1, (int) Math.ceil(sourceRight / mapScene.imageWidth * grid));
        int fromY = Math.max(0, (int) Math.floor(sourceTop / mapScene.imageHeight * grid));
        int toY = Math.min(grid - 1, (int) Math.ceil(sourceBottom / mapScene.imageHeight * grid));
        int paintedCells = 0;
        for (int cy = fromY; cy <= toY; cy++) {
            for (int cx = fromX; cx <= toX; cx++) {
                if (paint.isPainted(cx, cy)) {
                    paintedCells++;
                }
            }
        }
        if (paintedCells == 0) {
            return;
        }
        if (paintedCells <= TOO_MANY_CELLS) {
            drawPaintCells(canvas, mapScene, grid, fromX, toX, fromY, toY);
        } else {
            drawPaintOverlay(canvas, mapScene, grid);
        }
    }

    private void drawPaintCells(Canvas canvas, MapCatalog.Scene mapScene, int grid,
                                int fromX, int toX, int fromY, int toY) {
        float cellW = (float) (mapScene.imageWidth * scale / grid);
        float cellH = (float) (mapScene.imageHeight * scale / grid);
        float edge = Math.max(1f, dp(1.1f));
        edgePaint.setStrokeWidth(edge);
        for (int cy = fromY; cy <= toY; cy++) {
            for (int cx = fromX; cx <= toX; cx++) {
                if (!paint.isPainted(cx, cy)) {
                    continue;
                }
                float left = offsetX + cx * cellW;
                float top = offsetY + cy * cellH;
                canvas.drawRect(left, top, left + cellW, top + cellH, fillPaint);
                boolean up = paint.isPainted(cx, cy - 1);
                boolean down = paint.isPainted(cx, cy + 1);
                boolean leftPainted = paint.isPainted(cx - 1, cy);
                boolean rightPainted = paint.isPainted(cx + 1, cy);
                if (!up) {
                    canvas.drawLine(left, top, left + cellW, top, edgePaint);
                }
                if (!down) {
                    canvas.drawLine(left, top + cellH, left + cellW, top + cellH, edgePaint);
                }
                if (!leftPainted) {
                    canvas.drawLine(left, top, left, top + cellH, edgePaint);
                }
                if (!rightPainted) {
                    canvas.drawLine(left + cellW, top, left + cellW, top + cellH, edgePaint);
                }
            }
        }
    }

    private void drawPaintOverlay(Canvas canvas, MapCatalog.Scene mapScene, int grid) {
        if (paintOverlay == null || paintOverlayVersion != paint.getVersion()) {
            rebuildPaintOverlay(grid);
        }
        if (paintOverlay == null) {
            return;
        }
        scratchRect.set(offsetX, offsetY,
                offsetX + mapScene.imageWidth * scale,
                offsetY + mapScene.imageHeight * scale);
        canvas.drawBitmap(paintOverlay, null, scratchRect, bitmapPaint);
    }

    private void rebuildPaintOverlay(int grid) {
        if (paintOverlay != null) {
            paintOverlay.recycle();
        }
        try {
            paintOverlay = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            paintOverlay = null;
            return;
        }
        Canvas overlayCanvas = new Canvas(paintOverlay);
        for (int cy = 0; cy < grid; cy++) {
            for (int cx = 0; cx < grid; cx++) {
                if (!paint.isPainted(cx, cy)) {
                    continue;
                }
                overlayCanvas.drawRect(cx, cy, cx + 1, cy + 1, fillPaint);
            }
        }
        paintOverlayVersion = paint.getVersion();
    }

    private void drawSelfMarker(Canvas canvas, MapCatalog.Scene mapScene) {
        if (!Double.isFinite(selfX) || !Double.isFinite(selfY)) {
            return;
        }
        float x = offsetX + (float) (mapScene.imageX(selfX) * scale);
        float y = offsetY + (float) (mapScene.imageY(selfY) * scale);
        float radius = dp(9);
        if (x < -radius || y < -radius || x > getWidth() + radius || y > getHeight() + radius) {
            return;
        }
        canvas.drawCircle(x, y, radius + dp(3), markerGlowPaint);
        canvas.drawCircle(x, y, radius, markerFillPaint);
        canvas.drawCircle(x, y, radius - dp(1.2f), markerRingPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (scene == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                downTouchX = lastTouchX;
                downTouchY = lastTouchY;
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float x = event.getX();
                float y = event.getY();
                if (!dragging) {
                    float totalDx = x - downTouchX;
                    float totalDy = y - downTouchY;
                    if (Math.hypot(totalDx, totalDy) > touchSlop) {
                        dragging = true;
                        // Panning means the user wants to look elsewhere.
                        setFollowing(false);
                    }
                }
                if (dragging) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    if (dx != 0 || dy != 0) {
                        double worldPerPixel = scene.metresPerPixel() * 100.0;
                        centerX -= dx / scale * worldPerPixel;
                        centerY -= dy / scale * worldPerPixel;
                        invalidate();
                    }
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    performClick();
                }
                finishGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishGesture();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void finishGesture() {
        dragging = false;
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void notifyViewport() {
        if (viewportListener != null) {
            viewportListener.onViewportChanged(getZoomLabel(), canZoomIn(), canZoomOut());
        }
    }

    private void notifyFollow() {
        if (followListener != null) {
            followListener.onFollowChanged(following);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static String formatSpan(double span) {
        if (span >= 1000) {
            double km = span / 1000.0;
            if (Math.abs(km - Math.rint(km)) < 0.001) {
                return (int) Math.rint(km) + "km";
            }
            return String.format(java.util.Locale.US, "%.1fkm", km);
        }
        return (int) Math.rint(span) + "m";
    }

    private static void recycleQuietly(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
