package com.rocom.nearby.net;

import java.util.Arrays;

/**
 * Per-TCP-flow game protocol state: AES key, scene id, player position.
 *
 * <p>Only the map-relevant opcodes are handled: 307 self move, 348 teleport,
 * 338 enter scene. Pet/box/egg inventories are intentionally out of scope for
 * this milestone.</p>
 */
final class GameSession {
    interface Listener {
        void onMapPosition(int sceneId, double x, double y);

        /**
         * Fired as soon as a 338 enter-scene frame reveals the new scene, before
         * its first position update. The header follows the scene on its own.
         */
        void onSceneChanged(int sceneId);
    }

    private final Listener listener;
    private final GameCrypto crypto = new GameCrypto();
    private boolean keyVerified;
    private int sceneId;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;

    GameSession(Listener listener) {
        this.listener = listener;
    }

    void reset() {
        crypto.reset();
        keyVerified = false;
        sceneId = 0;
        selfX = Double.NaN;
        selfY = Double.NaN;
    }

    void processFrame(String direction, TgcpFramer.TgcpFrame frame) {
        int kind = frame.kind();
        if (kind == 0x1002) {
            if ("s2c".equals(direction) && frame.raw.length >= 39) {
                crypto.setKey(Arrays.copyOfRange(frame.raw, 23, 39));
                keyVerified = false;
            }
            return;
        }
        if (kind != 0x4013) {
            return;
        }
        int bodyOffset = frame.headerLength;
        int bodyLength = frame.bodyLength;
        if (bodyOffset < 0 || bodyLength <= 0
                || bodyOffset + bodyLength > frame.raw.length) {
            return;
        }
        byte[] plain = crypto.decrypt(frame.raw, bodyOffset, bodyLength);
        if (plain == null) {
            return;
        }
        boolean marker = plain.length >= 6
                && (plain[4] & 0xFF) == 0x55
                && (plain[5] & 0xFF) == 0xAA;

        if ("s2c".equals(direction)) {
            if (!keyVerified) {
                if (!marker) {
                    crypto.reset();
                    return;
                }
                keyVerified = true;
            } else if (!marker) {
                return;
            }
        }

        int opcodeOffset = "s2c".equals(direction) ? 2 : 6;
        if (plain.length < opcodeOffset + 2) {
            return;
        }
        int opcode = ((plain[opcodeOffset] & 0xFF) << 8)
                | (plain[opcodeOffset + 1] & 0xFF);

        if ("c2s".equals(direction) && opcode == 307) {
            GameProto.Position position = GameProto.parseSelfMove(plain);
            if (position != null) {
                selfX = position.x;
                selfY = position.y;
                notifyIfReady();
            }
            return;
        }
        if (opcode == 348) {
            GameProto.Teleport teleport = GameProto.parseTeleport(plain);
            if (teleport != null) {
                sceneId = teleport.sceneId;
                selfX = teleport.position.x;
                selfY = teleport.position.y;
                notifyIfReady();
            }
            return;
        }
        if (opcode == 338) {
            int newScene = GameProto.parseEnterScene(plain);
            if (newScene > 0 && newScene != sceneId) {
                sceneId = newScene;
                // The old position belongs to the previous scene; wait for a
                // new 307/348 update before showing the map again.
                selfX = Double.NaN;
                selfY = Double.NaN;
                listener.onSceneChanged(sceneId);
            }
        }
    }

    private void notifyIfReady() {
        if (sceneId <= 0 || !Double.isFinite(selfX) || !Double.isFinite(selfY)) {
            return;
        }
        listener.onMapPosition(sceneId, selfX, selfY);
    }
}
