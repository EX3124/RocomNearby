package com.rocom.nearby.net;

import android.util.Log;

import com.rocom.nearby.entity.NearbyEntity;

import java.util.Arrays;
import java.util.List;

final class GameSession {

    interface Listener {
        void onMapPosition(int sceneId, double x, double y);
        void onSceneChanged(int sceneId);
        void onEntities(List<NearbyEntity> entities);
    }

    private static final String TAG = "NearbySession";
    private final Listener listener;
    private final GameCrypto crypto = new GameCrypto();
    private final NearbyTracker tracker = new NearbyTracker();
    private boolean keyVerified;
    private int sceneId;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;

    GameSession(Listener listener) {
        this.listener = listener;
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
        if (opcode == 330 || opcode == 1043 || opcode == 1044) {
            long now = System.currentTimeMillis();
            if (tracker.consume(opcode, plain, now)) {
                listener.onEntities(tracker.snapshot(now));
            }
            return;
        }
        boolean battle = BattleTracker.handles(opcode);
        boolean caught = CaughtParser.carriesCaught(opcode);
        if (battle || caught) {
            long now = System.currentTimeMillis();
            boolean changed = false;
            if (battle) {
                changed = tracker.consumeBattle(opcode, plain, now);
            }
            if (caught) {
                List<CaughtParser.Caught> dossiers = CaughtParser.parse(plain);
                if (!dossiers.isEmpty()) {
                    int removed = tracker.consumeCaught(dossiers, now);
                    if (removed > 0) {
                        Log.i(TAG, "获得帧 opcode=" + opcode + " 档案="
                                + dossiers.size() + " 删卡=" + removed);
                        changed = true;
                    }
                }
            }
            if (changed) {
                listener.onEntities(tracker.snapshot(now));
            }
            return;
        }
        if ("c2s".equals(direction) && opcode == 307) {
            GameProto.Position position = GameProto.parseSelfMove(plain);
            if (position != null) {
                selfX = position.x;
                selfY = position.y;
                tracker.setSelfPosition(position.x, position.y);
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
                tracker.setSelfPosition(teleport.position.x, teleport.position.y);
                notifyIfReady();
            }
            return;
        }
        if (opcode == 338) {
            int newScene = GameProto.parseEnterScene(plain);
            if (newScene > 0 && newScene != sceneId) {
                sceneId = newScene;
                tracker.reset();
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
