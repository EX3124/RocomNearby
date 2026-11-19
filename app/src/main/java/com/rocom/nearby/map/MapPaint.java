package com.rocom.nearby.map;

public final class MapPaint {
    
    public static final double CELL_SIZE = 800.0;
    public static final double BRUSH_RADIUS = 8000.0;
    private final MapCatalog.Scene scene;
    private final int gridSize;
    private final byte[] bits;
    private int filled;
    private int version;

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

    public void step(double worldX, double worldY) {
        paintDisc(worldX, worldY, BRUSH_RADIUS);
    }

    public void reset() {
        java.util.Arrays.fill(bits, (byte) 0);
        filled = 0;
        version++;
    }

    private void paintDisc(double centreWorldX, double centreWorldY, double radius) {
        if (!Double.isFinite(centreWorldX) || !Double.isFinite(centreWorldY)) {
            return;
        }
        if (Math.abs(centreWorldX) < 100000 || Math.abs(centreWorldY) < 100000) {
            return;
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
            return;
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
            return;
        }
        filled += added;
        version++;
    }
}
