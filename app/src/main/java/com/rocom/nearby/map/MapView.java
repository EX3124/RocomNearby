package com.rocom.nearby.map;

import android.content.Context;
import android.content.res.Configuration;
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
import com.rocom.nearby.entity.Badge;
import com.rocom.nearby.entity.NearbyEntity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapView extends View {

    public interface OnViewportChangedListener {
        void onViewportChanged(String zoomLabel, boolean canZoomIn, boolean canZoomOut);
    }

    public interface OnFollowChangedListener {
        void onFollowChanged(boolean following);
    }

    private static final double[] ZOOM_SPANS = {100, 200, 400, 800, 1500, 3000};
    private static final double MIN_SPAN_M = ZOOM_SPANS[0];
    private static final double MAX_SPAN_M = ZOOM_SPANS[ZOOM_SPANS.length - 1];
    private static final double DEFAULT_SPAN_M = 200;
    private static final double SPAN_EPSILON_M = 1e-6;
    private static final double MAX_REGION_SPAN_M = 800.0;
    private static final long MAX_REGION_PIXELS = 4_000_000L;
    private static final int PAINT_FILL = Color.argb(82, 168, 85, 247);
    private static final int PAINT_EDGE = Color.argb(235, 233, 213, 255);
    private static final int TOO_MANY_CELLS = 6000;
    private static final float ENTITY_DOT_RADIUS_DP = 4f;
    private static final float PLAYER_DOT_RADIUS_DP = 5f;
    private static final float ENTITY_GLOW_PAD_DP = 1.5f;
    private static final float PLAYER_GLOW_PAD_DP = 2f;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private double spanMetres = DEFAULT_SPAN_M;
    private double centerX;
    private double centerY;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;
    private List<NearbyEntity> entities = Collections.emptyList();
    private int entityNormalColor;
    private int entitySpecialColor;
    private int entityPollutionColor;
    private boolean paintVisible;
    private boolean following = true;
    @Nullable
    private String trackedKey;
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
    private boolean multiTouch;
    private boolean pinching;
    private int pinchPrimaryId = MotionEvent.INVALID_POINTER_ID;
    private int pinchSecondaryId = MotionEvent.INVALID_POINTER_ID;
    private double pinchStartDistance;
    private double pinchStartSpan;
    private double pinchAnchorX;
    private double pinchAnchorY;
    @Nullable
    private String lastViewportLabel;
    private boolean lastCanZoomIn;
    private boolean lastCanZoomOut;

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
        markerFillPaint.setColor(context.getColor(R.color.map_player));
        entityFillPaint.setStyle(Paint.Style.FILL);
        entityNormalColor = context.getColor(R.color.map_normal);
        entitySpecialColor = context.getColor(R.color.map_special);
        entityPollutionColor = context.getColor(R.color.map_pollution);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(context.getColor(R.color.map_glow));
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
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
                setMeasuredDimension(width, height);
                return;
            }
            if (heightMode == MeasureSpec.EXACTLY && height > 0) {
                width = Math.round(height * 4f / 3f);
                if (widthMode == MeasureSpec.AT_MOST && width > MeasureSpec.getSize(widthMeasureSpec)) {
                    width = MeasureSpec.getSize(widthMeasureSpec);
                    height = Math.round(width * 3f / 4f);
                }
                setMeasuredDimension(width, height);
                return;
            }
            if (widthMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.UNSPECIFIED) {
                setMeasuredDimension(0, 0);
                return;
            }
        }
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            setMeasuredDimension(width, height);
            return;
        }
        if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
            setMeasuredDimension(width, height);
            return;
        }
        height = Math.round(width * 4f / 3f);
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
        entities = Collections.emptyList();
        trackedKey = null;
        if (newScene == null) {
            invalidate();
            return;
        }
        centerX = newScene.defaultFocusX;
        centerY = newScene.defaultFocusY;
        selfX = newScene.defaultFocusX;
        selfY = newScene.defaultFocusY;
        spanMetres = DEFAULT_SPAN_M;
        requestLayout();
        invalidate();
        ensureInitialized();
        notifyViewport();
    }

    public void setPaint(@Nullable MapPaint mapPaint) {
        if (paint == mapPaint) {
            invalidate();
            return;
        }
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

    public void setOnViewportChangedListener(@Nullable OnViewportChangedListener listener) {
        viewportListener = listener;
        lastViewportLabel = null;
        notifyViewport();
    }

    public void setOnFollowChangedListener(@Nullable OnFollowChangedListener listener) {
        followListener = listener;
        notifyFollow();
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean follow) {
        if (following == follow) {
            return;
        }
        following = follow;
        if (following) {
            trackedKey = null;
            panToSelf();
            invalidate();
        }
        notifyFollow();
    }

    public void toggleFollowing() {
        setFollowing(!following);
    }

    public void trackEntity(@Nullable NearbyEntity entity) {
        if (scene == null || entity == null
                || !Double.isFinite(entity.worldX)
                || !Double.isFinite(entity.worldY)) {
            return;
        }
        trackedKey = entity.identityKey();
        boolean wasFollowing = following;
        following = false;
        centerX = entity.worldX;
        centerY = entity.worldY;
        invalidate();
        if (wasFollowing) {
            notifyFollow();
        }
    }
    
    public void enterFreeMode() {
        trackedKey = null;
        setFollowing(false);
    }

    public void setEntities(@Nullable List<NearbyEntity> newEntities) {
        if (newEntities == null || newEntities.isEmpty()) {
            entities = Collections.emptyList();
        } else {
            entities = Collections.unmodifiableList(new ArrayList<>(newEntities));
        }
        followTracked();
        invalidate();
    }

    private void followTracked() {
        if (trackedKey == null) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            NearbyEntity candidate = entities.get(i);
            if (candidate == null || !trackedKey.equals(candidate.identityKey())) {
                continue;
            }
            if (!Double.isFinite(candidate.worldX)
                    || !Double.isFinite(candidate.worldY)) {
                return;
            }
            centerX = candidate.worldX;
            centerY = candidate.worldY;
            return;
        }
    }

    public void setSelf(double worldX, double worldY) {
        selfX = worldX;
        selfY = worldY;
        if (following) {
            centerX = worldX;
            centerY = worldY;
        }
        invalidate();
    }

    public double getSpanMetres() {
        return Double.isFinite(spanMetres) ? spanMetres : DEFAULT_SPAN_M;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }
    
    public void restoreViewport(double worldCenterX, double worldCenterY, double span) {
        if (!Double.isFinite(worldCenterX) || !Double.isFinite(worldCenterY)) {
            return;
        }
        centerX = worldCenterX;
        centerY = worldCenterY;
        spanMetres = clampSpan(span);
        invalidate();
        notifyViewport();
    }

    public String getZoomLabel() {
        return MapZoomMath.formatSpan(getSpanMetres());
    }

    public boolean canZoomIn() {
        return getSpanMetres() > MIN_SPAN_M + SPAN_EPSILON_M;
    }

    public boolean canZoomOut() {
        return getSpanMetres() < MAX_SPAN_M - SPAN_EPSILON_M;
    }

    public void zoomIn() {
        setSpanMetres(MapZoomMath.zoomInSpan(ZOOM_SPANS, getSpanMetres()));
    }

    public void zoomOut() {
        setSpanMetres(MapZoomMath.zoomOutSpan(ZOOM_SPANS, getSpanMetres()));
    }

    private void setSpanMetres(double span) {
        double clamped = clampSpan(span);
        if (Math.abs(clamped - spanMetres) < SPAN_EPSILON_M) {
            return;
        }
        spanMetres = clamped;
        reanchorAfterZoom();
        invalidate();
        notifyViewport();
    }

    private static double clampSpan(double span) {
        if (!Double.isFinite(span)) {
            return DEFAULT_SPAN_M;
        }
        return Math.max(MIN_SPAN_M, Math.min(MAX_SPAN_M, span));
    }

    private void reanchorAfterZoom() {
        if (following) {
            panToSelf();
        } else if (trackedKey != null) {
            followTracked();
        }
    }

    public void recenter() {
        if (scene == null) {
            return;
        }
        boolean followChanged = !following;
        following = true;
        trackedKey = null;
        panToSelf();
        spanMetres = DEFAULT_SPAN_M;
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
                    recycleQuietly(result);
                    return;
                }
                regionRequestPending = false;
                if (released || scene == null) {
                    recycleQuietly(result);
                    return;
                }
                if (result == null) {
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
        drawEntityMarkers(canvas, scene);
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

    private void drawEntityMarkers(Canvas canvas, MapCatalog.Scene mapScene) {
        if (entities.isEmpty()) {
            return;
        }
        float radius = dp(ENTITY_DOT_RADIUS_DP);
        float glowRadius = radius + dp(ENTITY_GLOW_PAD_DP);
        float margin = glowRadius;
        int width = getWidth();
        int height = getHeight();
        for (int i = 0; i < entities.size(); i++) {
            NearbyEntity entity = entities.get(i);
            if (entity == null
                    || !Double.isFinite(entity.worldX)
                    || !Double.isFinite(entity.worldY)) {
                continue;
            }
            float x = offsetX + (float) (mapScene.imageX(entity.worldX) * scale);
            float y = offsetY + (float) (mapScene.imageY(entity.worldY) * scale);
            if (x < -margin || y < -margin || x > width + margin || y > height + margin) {
                continue;
            }
            entityFillPaint.setColor(entityColor(entity));
            canvas.drawCircle(x, y, glowRadius, glowPaint);
            canvas.drawCircle(x, y, radius, entityFillPaint);
        }
    }

    private int entityColor(NearbyEntity entity) {
        if (entity.hasBadge(Badge.Kind.POLLUTION)) {
            return entityPollutionColor;
        }
        if (!entity.badges.isEmpty()) {
            return entitySpecialColor;
        }
        return entityNormalColor;
    }

    private void drawSelfMarker(Canvas canvas, MapCatalog.Scene mapScene) {
        if (!Double.isFinite(selfX) || !Double.isFinite(selfY)) {
            return;
        }
        float x = offsetX + (float) (mapScene.imageX(selfX) * scale);
        float y = offsetY + (float) (mapScene.imageY(selfY) * scale);
        float radius = dp(PLAYER_DOT_RADIUS_DP);
        if (x < -radius || y < -radius || x > getWidth() + radius || y > getHeight() + radius) {
            return;
        }
        canvas.drawCircle(x, y, radius + dp(PLAYER_GLOW_PAD_DP), glowPaint);
        canvas.drawCircle(x, y, radius, markerFillPaint);
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
                multiTouch = false;
                pinching = false;
                pinchPrimaryId = MotionEvent.INVALID_POINTER_ID;
                pinchSecondaryId = MotionEvent.INVALID_POINTER_ID;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                if (!pinching) {
                    beginPinch(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching) {
                    movePinch(event);
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                if (!dragging) {
                    float totalDx = x - downTouchX;
                    float totalDy = y - downTouchY;
                    if (Math.hypot(totalDx, totalDy) > touchSlop) {
                        dragging = true;
                        enterFreeMode();
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
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching) {
                    endPinch(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && !multiTouch) {
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

    private void beginPinch(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return;
        }
        double startDistance = pointerDistance(event, 0, 1);
        if (!(startDistance > 0)) {
            return;
        }
        pinchPrimaryId = event.getPointerId(0);
        pinchSecondaryId = event.getPointerId(1);
        pinchStartDistance = startDistance;
        pinchStartSpan = getSpanMetres();
        dragging = false;
        pinching = true;
        enterFreeMode();
        anchorPinch(focusX(event, 0, 1), focusY(event, 0, 1));
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void anchorPinch(float screenX, float screenY) {
        if (scene == null || getWidth() <= 0 || getHeight() <= 0) {
            pinchAnchorX = centerX;
            pinchAnchorY = centerY;
            return;
        }
        double scale = sourceScale(getSpanMetres());
        pinchAnchorX = scene.worldX(
                MapZoomMath.sourceAtScreen(scene.imageX(centerX), screenX, getWidth(), scale));
        pinchAnchorY = scene.worldY(
                MapZoomMath.sourceAtScreen(scene.imageY(centerY), screenY, getHeight(), scale));
    }

    private void movePinch(MotionEvent event) {
        int firstIndex = event.findPointerIndex(pinchPrimaryId);
        int secondIndex = event.findPointerIndex(pinchSecondaryId);
        if (firstIndex < 0 || secondIndex < 0) {
            pinching = false;
            return;
        }
        double target = MapZoomMath.spanForPinch(pinchStartSpan, pinchStartDistance,
                pointerDistance(event, firstIndex, secondIndex), MIN_SPAN_M, MAX_SPAN_M);
        boolean zoomed = Math.abs(target - spanMetres) > SPAN_EPSILON_M;
        if (zoomed) {
            spanMetres = target;
        }
        applyPinchFocus(focusX(event, firstIndex, secondIndex),
                focusY(event, firstIndex, secondIndex));
        if (zoomed) {
            notifyViewport();
        }
        invalidate();
    }

    private void applyPinchFocus(float screenX, float screenY) {
        if (scene == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double scale = sourceScale(getSpanMetres());
        centerX = scene.worldX(MapZoomMath.centreForSourceAtScreen(
                scene.imageX(pinchAnchorX), screenX, getWidth(), scale));
        centerY = scene.worldY(MapZoomMath.centreForSourceAtScreen(
                scene.imageY(pinchAnchorY), screenY, getHeight(), scale));
    }

    private double sourceScale(double span) {
        if (scene == null || getWidth() <= 0) {
            return 1.0;
        }
        double sourceWidth = span / scene.metresPerPixel();
        return sourceWidth > 0 ? getWidth() / sourceWidth : 1.0;
    }

    private void endPinch(MotionEvent event) {
        pinching = false;
        pinchPrimaryId = MotionEvent.INVALID_POINTER_ID;
        pinchSecondaryId = MotionEvent.INVALID_POINTER_ID;
        int keepIndex = event.getActionIndex() == 0 ? 1 : 0;
        if (keepIndex >= event.getPointerCount()) {
            keepIndex = 0;
        }
        lastTouchX = event.getX(keepIndex);
        lastTouchY = event.getY(keepIndex);
        downTouchX = lastTouchX;
        downTouchY = lastTouchY;
        dragging = false;
    }

    private static double pointerDistance(MotionEvent event, int firstIndex, int secondIndex) {
        double dx = event.getX(firstIndex) - event.getX(secondIndex);
        double dy = event.getY(firstIndex) - event.getY(secondIndex);
        return Math.hypot(dx, dy);
    }

    private static float focusX(MotionEvent event, int firstIndex, int secondIndex) {
        return (event.getX(firstIndex) + event.getX(secondIndex)) / 2f;
    }

    private static float focusY(MotionEvent event, int firstIndex, int secondIndex) {
        return (event.getY(firstIndex) + event.getY(secondIndex)) / 2f;
    }

    private void finishGesture() {
        dragging = false;
        pinching = false;
        multiTouch = false;
        pinchPrimaryId = MotionEvent.INVALID_POINTER_ID;
        pinchSecondaryId = MotionEvent.INVALID_POINTER_ID;
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
        if (viewportListener == null) {
            return;
        }
        String label = getZoomLabel();
        boolean canIn = canZoomIn();
        boolean canOut = canZoomOut();
        if (label.equals(lastViewportLabel)
                && canIn == lastCanZoomIn && canOut == lastCanZoomOut) {
            return;
        }
        lastViewportLabel = label;
        lastCanZoomIn = canIn;
        lastCanZoomOut = canOut;
        viewportListener.onViewportChanged(label, canIn, canOut);
    }

    private void notifyFollow() {
        if (followListener != null) {
            followListener.onFollowChanged(following);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static void recycleQuietly(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
