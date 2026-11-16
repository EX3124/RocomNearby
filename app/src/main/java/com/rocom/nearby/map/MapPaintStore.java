package com.rocom.nearby.map;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Process-wide explored-area recorder.
 *
 * <p>Recording lives here rather than in the map card so the trail keeps
 * growing while the user is in the game and the card is hidden.</p>
 *
 * <p>Two deliberate limits:</p>
 * <ul>
 *   <li>recording only happens while the 染色 layer is switched on, so nothing
 *       is collected behind the user's back;</li>
 *   <li>nothing is persisted. The explored area belongs to the current run and
 *       is discarded when the process ends.</li>
 * </ul>
 *
 * <p>All {@link MapPaint} mutations run on the main looper so the map view can
 * read the same bitset safely while drawing.</p>
 */
public final class MapPaintStore {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Prefs file used by builds that still persisted the grid; wiped once. */
    private static final String LEGACY_PREFS = "nearby_map_paint";

    @Nullable
    private static MapPaint paint;
    private static int paintSceneId;
    /**
     * The 染色 switch. Recording is skipped entirely while it is off. It lives
     * in the process (so it survives a configuration change) and starts off on
     * every launch.
     */
    private static volatile boolean enabled;

    private MapPaintStore() {
    }

    public static void initialize(Context context) {
        // Older builds wrote the grid to disk. Delete the file so "关闭软件即
        // 销毁" also holds for leftovers from a previous install. Only the file
        // is touched: going through SharedPreferences would immediately create
        // it again.
        try {
            java.io.File prefsDir = new java.io.File(
                    context.getApplicationContext().getApplicationInfo().dataDir,
                    "shared_prefs");
            for (String name : new String[]{LEGACY_PREFS + ".xml", LEGACY_PREFS + ".xml.bak"}) {
                java.io.File file = new java.io.File(prefsDir, name);
                if (file.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        } catch (Throwable ignored) {
            // A read-only data dir must not break the map.
        }
    }

    /** True while the 染色 layer is on and positions are being recorded. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Turns recording on/off; enabling starts a fresh trail. */
    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        MAIN.post(() -> {
            if (paint != null && value) {
                paint.startNewTrail();
            }
        });
    }

    /** Called for every decoded 307/348 position, from the capture thread. */
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

    /** Returns the recorder for the given scene; UI thread only. */
    @Nullable
    public static MapPaint get(int sceneId) {
        return ensure(sceneId);
    }

    /** Drops the in-memory grid, e.g. when a new capture session starts. */
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
