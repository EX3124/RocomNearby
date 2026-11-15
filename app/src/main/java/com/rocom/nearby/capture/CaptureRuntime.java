package com.rocom.nearby.capture;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Process-local state shared by {@link PcapCaptureService} and the nearby screen.
 *
 * <p>The capture service owns the socket and the pcap file. The activity only
 * observes its latest snapshot, which makes the screen safe to recreate while a
 * capture is running (for example when the user returns from PCAPdroid).</p>
 */
public final class CaptureRuntime {
    public enum State {
        IDLE,
        STARTING,
        LISTENING,
        CAPTURING,
        ERROR
    }

    public static final class Snapshot {
        public final State state;
        public final long bytes;
        public final int packets;
        @Nullable
        public final String filePath;
        public final String message;
        public final int port;
        public final boolean connected;

        public Snapshot(State state, long bytes, int packets, @Nullable String filePath,
                        String message, int port, boolean connected) {
            this.state = state;
            this.bytes = bytes;
            this.packets = packets;
            this.filePath = filePath;
            this.message = message;
            this.port = port;
            this.connected = connected;
        }
    }

    public interface Listener {
        void onCaptureSnapshot(Snapshot snapshot);

        void onServerReady(int port);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Snapshot current = new Snapshot(
            State.IDLE, 0, 0, null, "", 0, false);
    private static Listener listener;

    private CaptureRuntime() {
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

    public static void publish(Snapshot snapshot) {
        current = snapshot;
        MAIN.post(() -> {
            Listener target = listener;
            if (target != null) {
                target.onCaptureSnapshot(snapshot);
            }
        });
    }

    public static void publish(State state, long bytes, int packets, @Nullable String filePath,
                               String message, int port, boolean connected) {
        publish(new Snapshot(state, bytes, packets, filePath, message, port, connected));
    }

    public static void notifyServerReady(int port) {
        MAIN.post(() -> {
            Listener target = listener;
            if (target != null) {
                target.onServerReady(port);
            }
        });
    }
}
