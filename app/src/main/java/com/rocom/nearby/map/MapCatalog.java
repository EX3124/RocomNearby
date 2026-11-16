package com.rocom.nearby.map;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Static information the app needs to draw a scene map.
 *
 * <p>The values mirror the scene tables in the reference uni-app
 * ({@code app-service.js}): each scene has a square world origin / side and a
 * square image. World units are centimetres, so a side of 408000 means the
 * image covers 4080 metres. Only the scenes whose base image is bundled in
 * {@code assets/maps} are listed; unknown scenes deliberately have no map.</p>
 */
public final class MapCatalog {
    private static final Map<Integer, Scene> SCENES = new HashMap<>();

    static {
        // 卡洛西亚大陆, the only base image shipped with the reference build.
        // The image is 4096x4096 and the scene square is 408000x408000 cm.
        SCENES.put(10003, new Scene(
                10003,
                "卡洛西亚大陆",
                306000.0,
                408000.0,
                408000.0,
                4096,
                4096,
                "maps/10003.webp",
                // Middle of the main town on the southern coast. Until the
                // tracker can supply the player's real position, this is the
                // scene's default view focus (and the map's self marker).
                442913.09,
                674156.25));
    }

    private MapCatalog() {
    }

    @Nullable
    public static Scene find(int sceneId) {
        return SCENES.get(sceneId);
    }

    /** Immutable description of one scene map. */
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

        Scene(int sceneId, String name, double originX, double originY, double side,
              int imageWidth, int imageHeight, String assetPath,
              double defaultFocusX, double defaultFocusY) {
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

        /** Metres covered by one image pixel. */
        public double metresPerPixel() {
            return side / 100.0 / imageWidth;
        }

        /** Maps a world position to a pixel position in the base image. */
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
