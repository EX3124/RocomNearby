package com.rocom.nearby.map;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

public final class MapPaintStore {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String LEGACY_PREFS = "nearby_map_paint";
    @Nullable
    private static MapPaint paint;
    private static int paintSceneId;
    private static volatile boolean enabled;

    public static void initialize(Context context) {
        try {
            java.io.File prefsDir = new java.io.File(
                    context.getApplicationContext().getApplicationInfo().dataDir,
                    "shared_prefs");
            for (String name : new String[]{LEGACY_PREFS + ".xml", LEGACY_PREFS + ".xml.bak"}) {
                java.io.File file = new java.io.File(prefsDir, name);
                if (file.isFile()) {
                    
                    file.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }
    
    public static void setEnabled(boolean value) {
        enabled = value;
    }
    
    public static void record(int sceneId, double x, double y) {
        if (!enabled || !Double.isFinite(x) || !Double.isFinite(y)) {
            return;
        }
        MAIN.post(() -> {
            MapPaint target = ensure(sceneId);
            if (target != null) {
                target.step(x, y);
            }
        });
    }

    @Nullable
    public static MapPaint get(int sceneId) {
        return ensure(sceneId);
    }

    public static void resetSession() {
        MAIN.post(() -> {
            paint = null;
            paintSceneId = 0;
        });
    }

    @Nullable
    private static MapPaint ensure(int sceneId) {
        if (paint != null && paintSceneId == sceneId) {
            return paint;
        }
        MapCatalog.Scene scene = MapCatalog.find(sceneId);
        if (scene == null) {
            paint = null;
            paintSceneId = 0;
            return null;
        }
        paint = MapPaint.create(scene);
        paintSceneId = sceneId;
        return paint;
    }
}
