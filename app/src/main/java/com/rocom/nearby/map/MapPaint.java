package com.rocom.nearby.map;

/**
 * Explored-area ("染色") bitmap for one scene.
 *
 * <p>The reference app records the player's path into a square bit grid
 * (800 world-unit cells, 8000 world-unit brush) and paints every visited cell
 * in translucent purple. The model is reproduced here so the map layer and the
 * clear action behave the same.</p>
 *
 * <p>Deliberately memory-only: the explored area belongs to the current run and
 * disappears when the process ends. Nothing is written to disk.</p>
 */
public final class MapPaint {
    /** Reference grid: one cell every 800 cm (8 m). */
    public static final double CELL_SIZE = 800.0;
    /** Reference brush radius: 8000 cm (80 m). */
    public static final double BRUSH_RADIUS = 8000.0;

    private final MapCatalog.Scene scene;
    private final int gridSize;
    private final byte[] bits;

    private int filled;
    private int version;
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;

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

    public int getFilledCount() {
        return filled;
    }

    public boolean isPainted(int cellX, int cellY) {
        if (cellX < 0 || cellY < 0 || cellX >= gridSize || cellY >= gridSize) {
            return false;
        }
        int index = cellY * gridSize + cellX;
        return (bits[index >> 3] & (1 << (index & 7))) != 0;
    }

    /**
     * Records a position update. Consecutive calls are joined with a thick
     * segment, matching the reference tracker's walk path.
     */
    public void step(double worldX, double worldY) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) {
            return;
        }
        if (Double.isNaN(lastX)) {
            applySegment(worldX, worldY, worldX, worldY, BRUSH_RADIUS);
        } else {
            double dx = worldX - lastX;
            double dy = worldY - lastY;
            if (dx * dx + dy * dy > 400000.0 * 400000.0) {
                // A teleport (scene change / long jump) starts a new trail.
                applySegment(worldX, worldY, worldX, worldY, BRUSH_RADIUS);
            } else {
                applySegment(lastX, lastY, worldX, worldY, BRUSH_RADIUS);
            }
        }
        lastX = worldX;
        lastY = worldY;
    }

    /**
     * Starts a new trail: the next {@link #step} records a single cell instead
     * of drawing a long segment from the previous position. Used when the
     * explored-area layer is switched on after a pause.
     */
    public void startNewTrail() {
        lastX = Double.NaN;
        lastY = Double.NaN;
    }

    public void reset() {
        java.util.Arrays.fill(bits, (byte) 0);
        filled = 0;
        lastX = Double.NaN;
        lastY = Double.NaN;
        version++;
    }

    private void applySegment(double x1, double y1, double x2, double y2, double radius) {
        if (!Double.isFinite(x1) || !Double.isFinite(y1)
                || !Double.isFinite(x2) || !Double.isFinite(y2)) {
            return;
        }
        // Reference rejects positions outside the world square.
        if (Math.abs(x1) < 100000 || Math.abs(x2) < 100000
                || Math.abs(y1) < 100000 || Math.abs(y2) < 100000) {
            return;
        }
        double minX = Math.min(x1, x2) - radius;
        double maxX = Math.max(x1, x2) + radius;
        double minY = Math.min(y1, y2) - radius;
        double maxY = Math.max(y1, y2) + radius;
        int fromX = Math.max(0, (int) Math.floor((minX - scene.originX) / CELL_SIZE));
        int toX = Math.min(gridSize - 1, (int) Math.floor((maxX - scene.originX) / CELL_SIZE));
        int fromY = Math.max(0, (int) Math.floor((minY - scene.originY) / CELL_SIZE));
        int toY = Math.min(gridSize - 1, (int) Math.floor((maxY - scene.originY) / CELL_SIZE));
        if (fromX > toX || fromY > toY) {
            return;
        }
        int added = 0;
        for (int cy = fromY; cy <= toY; cy++) {
            double centreY = scene.originY + (cy + 0.5) * CELL_SIZE;
            for (int cx = fromX; cx <= toX; cx++) {
                if (isPainted(cx, cy)) {
                    continue;
                }
                double centreX = scene.originX + (cx + 0.5) * CELL_SIZE;
                if (distanceToSegment(centreX, centreY, x1, y1, x2, y2) > radius) {
                    continue;
                }
                int index = cy * gridSize + cx;
                bits[index >> 3] |= (byte) (1 << (index & 7));
                added++;
            }
        }
        if (added == 0) {
            return;
        }
        filled += added;
        version++;
    }

    private static double distanceToSegment(double px, double py,
                                            double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSq = dx * dx + dy * dy;
        double t = 0;
        if (lengthSq > 0) {
            t = ((px - x1) * dx + (py - y1) * dy) / lengthSq;
            t = Math.max(0, Math.min(1, t));
        }
        double nearestX = x1 + t * dx;
        double nearestY = y1 + t * dy;
        return Math.hypot(px - nearestX, py - nearestY);
    }
}
