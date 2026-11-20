package com.rocom.nearby.map;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MapPaint {

    public interface Listener {
        void onPaintChanged(int sceneId);
    }

    public static final double CELL_SIZE = 800.0;
    public static final double BRUSH_RADIUS = 8000.0;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Integer, MapPaint> PAINTS = new HashMap<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean enabled;
    private final MapCatalog.Scene scene;
    private final int gridSize;
    private final byte[] bits;
    private int version;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        notifyChanged(0);
    }

    @Nullable
    public static MapPaint get(int sceneId) {
        if (sceneId <= 0) {
            return null;
        }
        MapPaint existing = PAINTS.get(sceneId);
        if (existing != null) {
            return existing;
        }
        MapCatalog.Scene scene = MapCatalog.find(sceneId);
        if (scene == null) {
            return null;
        }
        MapPaint created = create(scene);
        PAINTS.put(sceneId, created);
        return created;
    }

    public static void record(final int sceneId, final double x, final double y) {
        if (!enabled || !Double.isFinite(x) || !Double.isFinite(y)) {
            return;
        }
        MAIN.post(() -> {
            if (!enabled) {
                return;
            }
            MapPaint target = get(sceneId);
            if (target == null) {
                return;
            }
            if (target.step(x, y)) {
                notifyChanged(sceneId);
            }
        });
    }

    public static void clear(final int sceneId) {
        MAIN.post(() -> {
            MapPaint target = PAINTS.get(sceneId);
            if (target == null) {
                return;
            }
            target.reset();
            notifyChanged(sceneId);
        });
    }

    public static void addListener(@Nullable Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(@Nullable Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyChanged(int sceneId) {
        for (Listener listener : LISTENERS) {
            listener.onPaintChanged(sceneId);
        }
    }

    private MapPaint(MapCatalog.Scene scene) {
        this.scene = scene;
        this.gridSize = (int) Math.ceil(scene.side / CELL_SIZE);
        this.bits = new byte[(gridSize * gridSize + 7) / 8];
    }

    public static MapPaint create(MapCatalog.Scene scene) {
        return new MapPaint(scene);
    }

    public int getGridSize() {
        return gridSize;
    }

    public int getVersion() {
        return version;
    }

    public boolean isPainted(int cellX, int cellY) {
        if (cellX < 0 || cellY < 0 || cellX >= gridSize || cellY >= gridSize) {
            return false;
        }
        int index = cellY * gridSize + cellX;
        return (bits[index >> 3] & (1 << (index & 7))) != 0;
    }

    public boolean step(double worldX, double worldY) {
        return paintDisc(worldX, worldY, BRUSH_RADIUS);
    }

    public void reset() {
        Arrays.fill(bits, (byte) 0);
        version++;
    }

    private boolean paintDisc(double centreWorldX, double centreWorldY, double radius) {
        if (!Double.isFinite(centreWorldX) || !Double.isFinite(centreWorldY)) {
            return false;
        }
        int fromX = Math.max(0,
                (int) Math.floor((centreWorldX - radius - scene.originX) / CELL_SIZE));
        int toX = Math.min(gridSize - 1,
                (int) Math.floor((centreWorldX + radius - scene.originX) / CELL_SIZE));
        int fromY = Math.max(0,
                (int) Math.floor((centreWorldY - radius - scene.originY) / CELL_SIZE));
        int toY = Math.min(gridSize - 1,
                (int) Math.floor((centreWorldY + radius - scene.originY) / CELL_SIZE));
        if (fromX > toX || fromY > toY) {
            return false;
        }
        double radiusSq = radius * radius;
        int added = 0;
        for (int cy = fromY; cy <= toY; cy++) {
            double deltaY = scene.originY + (cy + 0.5) * CELL_SIZE - centreWorldY;
            for (int cx = fromX; cx <= toX; cx++) {
                if (isPainted(cx, cy)) {
                    continue;
                }
                double deltaX = scene.originX + (cx + 0.5) * CELL_SIZE - centreWorldX;
                if (deltaX * deltaX + deltaY * deltaY > radiusSq) {
                    continue;
                }
                int index = cy * gridSize + cx;
                bits[index >> 3] |= (byte) (1 << (index & 7));
                added++;
            }
        }
        if (added == 0) {
            return false;
        }
        version++;
        return true;
    }
}
