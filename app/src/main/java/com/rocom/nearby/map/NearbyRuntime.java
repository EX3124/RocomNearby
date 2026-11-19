package com.rocom.nearby.map;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.rocom.nearby.entity.NearbyEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NearbyRuntime {

    private static final List<NearbyEntity> NO_ENTITIES = Collections.emptyList();

    public static final class Snapshot {
        public final int sceneId;
        public final boolean hasPosition;
        public final double selfX;
        public final double selfY;
        public final List<NearbyEntity> entities;

        Snapshot(int sceneId, boolean hasPosition, double selfX, double selfY) {
            this(sceneId, hasPosition, selfX, selfY, NO_ENTITIES);
        }

        Snapshot(int sceneId, boolean hasPosition, double selfX, double selfY,
                 List<NearbyEntity> entities) {
            this.sceneId = sceneId;
            this.hasPosition = hasPosition;
            this.selfX = selfX;
            this.selfY = selfY;
            this.entities = entities;
        }
    }

    public interface Listener {
        void onNearbySnapshot(Snapshot snapshot);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Snapshot current = new Snapshot(0, false, 0, 0);
    private static Listener listener;
    private static final CopyOnWriteArrayList<Listener> extraListeners =
            new CopyOnWriteArrayList<>();

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

    public static void addListener(@Nullable Listener extra) {
        if (extra != null) {
            extraListeners.addIfAbsent(extra);
        }
    }

    public static void removeListener(Listener extra) {
        extraListeners.remove(extra);
    }

    public static void publishPosition(int sceneId, double worldX, double worldY) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) {
            return;
        }
        Snapshot previous = current;
        List<NearbyEntity> entities = previous.sceneId == sceneId || previous.sceneId == 0
                ? previous.entities
                : NO_ENTITIES;
        publish(new Snapshot(sceneId, true, worldX, worldY, entities));
    }

    public static void publishEntities(@Nullable List<NearbyEntity> entities) {
        Snapshot previous = current;
        publish(new Snapshot(previous.sceneId, previous.hasPosition,
                previous.selfX, previous.selfY, freeze(entities)));
    }

    private static List<NearbyEntity> freeze(@Nullable List<NearbyEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return NO_ENTITIES;
        }
        return Collections.unmodifiableList(new ArrayList<>(entities));
    }

    public static void clear() {
        publish(new Snapshot(0, false, 0, 0));
    }

    public static void publishScene(int sceneId) {
        if (sceneId <= 0) {
            return;
        }
        publish(new Snapshot(sceneId, false, 0, 0));
    }

    private static void publish(Snapshot snapshot) {
        current = snapshot;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(snapshot);
            return;
        }
        MAIN.post(() -> dispatch(snapshot));
    }

    private static void dispatch(Snapshot snapshot) {
        Listener target = listener;
        if (target != null) {
            target.onNearbySnapshot(snapshot);
        }
        for (Listener extra : extraListeners) {
            extra.onNearbySnapshot(snapshot);
        }
    }
}
