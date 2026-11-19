package com.rocom.nearby.net;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;

final class TcpReassembler {

    private long nextSequence = -1;
    private final TreeMap<Long, byte[]> pending = new TreeMap<>();

    byte[] feed(long sequence, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        if (nextSequence < 0) {
            nextSequence = sequence;
        }
        long end = sequence + payload.length;
        if (end <= nextSequence) {
            return new byte[0];
        }
        if (sequence < nextSequence) {
            int skip = (int) (nextSequence - sequence);
            if (skip >= payload.length) {
                return new byte[0];
            }
            byte[] trimmed = new byte[payload.length - skip];
            System.arraycopy(payload, skip, trimmed, 0, trimmed.length);
            payload = trimmed;
            sequence = nextSequence;
        }
        if (sequence > nextSequence) {
            byte[] old = pending.get(sequence);
            if (old == null || payload.length > old.length) {
                pending.put(sequence, payload);
            }
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        out.write(payload, 0, payload.length);
        nextSequence += payload.length;
        drain(out);
        return out.toByteArray();
    }

    private void drain(ByteArrayOutputStream out) {
        while (!pending.isEmpty()) {
            Map.Entry<Long, byte[]> first = pending.firstEntry();
            long sequence = first.getKey();
            byte[] payload = first.getValue();
            long end = sequence + payload.length;
            if (end <= nextSequence) {
                pending.pollFirstEntry();
                continue;
            }
            if (sequence > nextSequence) {
                return;
            }
            int skip = (int) (nextSequence - sequence);
            out.write(payload, skip, payload.length - skip);
            nextSequence += payload.length - skip;
            pending.pollFirstEntry();
        }
    }
}
