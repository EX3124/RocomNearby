package com.rocom.nearby.map;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class MapCatalog {

    private static final Map<Integer, Scene> SCENES = new HashMap<>();

    static {
        SCENES.put(10003, new Scene(
                10003,
                "卡洛西亚大陆",
                306000.0,
                408000.0,
                408000.0,
                4096,
                4096,
                "maps/10003.webp",
                80882,
                198974));
    }

    @Nullable
    public static Scene find(int sceneId) {
        return SCENES.get(sceneId);
    }

    public static final class Scene {

        public final int sceneId;
        public final String name;
        public final double originX;
        public final double originY;
        public final double side;
        public final int imageWidth;
        public final int imageHeight;
        public final String assetPath;
        public final double defaultFocusX;
        public final double defaultFocusY;

        Scene(int sceneId, String name, double originX, double originY, double side, int imageWidth, int imageHeight, String assetPath, double defaultFocusX, double defaultFocusY) {
            this.sceneId = sceneId;
            this.name = name;
            this.originX = originX;
            this.originY = originY;
            this.side = side;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.assetPath = assetPath;
            this.defaultFocusX = defaultFocusX;
            this.defaultFocusY = defaultFocusY;
        }

        public double metresPerPixel() {
            return side / 100.0 / imageWidth;
        }

        public double imageX(double worldX) {
            return (worldX - originX) / side * imageWidth;
        }

        public double imageY(double worldY) {
            return (worldY - originY) / side * imageHeight;
        }

        public double worldX(double imageX) {
            return originX + imageX / imageWidth * side;
        }

        public double worldY(double imageY) {
            return originY + imageY / imageHeight * side;
        }
    }
}
