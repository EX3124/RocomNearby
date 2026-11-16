package com.rocom.nearby.map;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Process-local nearby state shared by the pcap tracker and the screen.
 *
 * <p>This is the gate for the map section: a scene map is only shown after the
 * tracker has published a real world position for the player. Receiving pcap
 * bytes alone is deliberately not enough — without coordinates the map would
 * just be a decorative image.</p>
 */
public final class NearbyRuntime {
    public static final class Snapshot {
        public final int sceneId;
        public final boolean hasPosition;
        public final double selfX;
        public final double selfY;

        Snapshot(int sceneId, boolean hasPosition, double selfX, double selfY) {
            this.sceneId = sceneId;
            this.hasPosition = hasPosition;
            this.selfX = selfX;
            this.selfY = selfY;
        }
    }

    public interface Listener {
        void onNearbySnapshot(Snapshot snapshot);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Snapshot current = new Snapshot(0, false, 0, 0);
    private static Listener listener;

    private NearbyRuntime() {
    }

    public static Snapshot current() {
        return current;
    }

    public static void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    public static void clearListener(Listener oldListener) {
        if (listener == oldListener) {
            listener = null;
        }
    }

    /**
     * Called by the tracker once it has solved the player's world position
     * (centimetres, same coordinate space as {@link MapCatalog}).
     */
    public static void publishPosition(int sceneId, double worldX, double worldY) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) {
            return;
        }
        publish(new Snapshot(sceneId, true, worldX, worldY));
    }

    /** Called when capture stops, the scene changes or the data goes stale. */
    public static void clear() {
        publish(new Snapshot(0, false, 0, 0));
    }

    /**
     * Announces a new scene before its first position update, so the header can
     * follow the scene immediately. The previous scene's coordinates are
     * dropped: they do not describe the new scene.
     */
    public static void publishScene(int sceneId) {
        if (sceneId <= 0) {
            return;
        }
        publish(new Snapshot(sceneId, false, 0, 0));
    }

    private static void publish(Snapshot snapshot) {
        current = snapshot;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Keep clear/start ordering synchronous on the UI thread.
            Listener target = listener;
            if (target != null) {
                target.onNearbySnapshot(snapshot);
            }
            return;
        }
        MAIN.post(() -> {
            Listener target = listener;
            if (target != null) {
                target.onNearbySnapshot(snapshot);
            }
        });
    }
}
