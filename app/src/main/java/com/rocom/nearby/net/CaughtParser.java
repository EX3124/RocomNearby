package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.List;

final class CaughtParser {

    private static final int[] OPCODES = {780, 6531, 579, 352};
    private static final int HEADER_BYTES = 10;
    private static final int MAX_DEPTH = 6;
    private static final int MIN_RECORD_BYTES = 16;
    private static final int FIELD_GID = 1;
    private static final int FIELD_CONF_ID = 2;
    private static final int FIELD_BASE_CONF_ID = 15;
    private static final int FIELD_BASE_CONF_ID_FALLBACK = 42;
    private static final int FIELD_HEIGHT = 26;
    private static final int FIELD_WEIGHT = 27;
    private static final int FIELD_ADD_TIME = 32;
    private static final int FIELD_VOICE = 93;
    private static final long MIN_CONF_ID = 1000L;
    private static final long MIN_BASE_CONF_ID = 1000L;

    static final class Caught {

        final long gid;
        final long confId;
        final long baseConfId;
        final long height;
        final long weight;
        final long voice;
        final boolean hasVoice;
        final long addTime;

        Caught(long gid, long confId, long baseConfId, long height, long weight,
               long voice, boolean hasVoice, long addTime) {
            this.gid = gid;
            this.confId = confId;
            this.baseConfId = baseConfId;
            this.height = height;
            this.weight = weight;
            this.voice = voice;
            this.hasVoice = hasVoice;
            this.addTime = addTime;
        }
    }

    static boolean carriesCaught(int opcode) {
        for (int i = 0; i < OPCODES.length; i++) {
            if (OPCODES[i] == opcode) {
                return true;
            }
        }
        return false;
    }

    static List<Caught> parse(byte[] plain) {
        List<Caught> out = new ArrayList<>();
        if (plain == null || plain.length <= HEADER_BYTES) {
            return out;
        }
        collect(plain, HEADER_BYTES, plain.length, 0, out);
        return out;
    }

    private static void collect(byte[] buf, int start, int end, int depth, List<Caught> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        List<ProtoScan.Field> fields = ProtoScan.scan(buf, start, end);
        for (int i = 0; i < fields.size(); i++) {
            ProtoScan.Field field = fields.get(i);
            if (field.wire != ProtoScan.WIRE_LENGTH) {
                continue;
            }
            Caught record = readRecord(buf, field.valueStart, field.valueEnd);
            if (record != null) {
                out.add(record);
                continue;
            }
            collect(buf, field.valueStart, field.valueEnd, depth + 1, out);
        }
    }

    private static Caught readRecord(byte[] buf, int start, int end) {
        if (end - start < MIN_RECORD_BYTES) {
            return null;
        }
        List<ProtoScan.Field> fields = ProtoScan.scan(buf, start, end);
        long gid = 0L;
        long confId = 0L;
        long baseConfId = 0L;
        long fallbackBaseConfId = 0L;
        long height = 0L;
        long weight = 0L;
        long voice = 0L;
        boolean voiceSeen = false;
        long addTime = 0L;
        for (int i = 0; i < fields.size(); i++) {
            ProtoScan.Field field = fields.get(i);
            if (field.wire != ProtoScan.WIRE_VARINT) {
                continue;
            }
            switch (field.number) {
                case FIELD_GID:
                    if (gid == 0L) {
                        gid = field.varint;
                    }
                    break;
                case FIELD_CONF_ID:
                    if (confId == 0L) {
                        confId = field.varint;
                    }
                    break;
                case FIELD_BASE_CONF_ID:
                    baseConfId = field.varint;
                    break;
                case FIELD_BASE_CONF_ID_FALLBACK:
                    fallbackBaseConfId = field.varint;
                    break;
                case FIELD_HEIGHT:
                    height = field.varint;
                    break;
                case FIELD_WEIGHT:
                    weight = field.varint;
                    break;
                case FIELD_ADD_TIME:
                    addTime = field.varint;
                    break;
                case FIELD_VOICE:
                    if (!voiceSeen) {
                        voice = field.varint;
                        voiceSeen = true;
                    }
                    break;
                default:
                    break;
            }
        }
        if (fields.isEmpty() || fields.get(fields.size() - 1).end != end) {
            return null;
        }
        if (gid <= 0L || confId <= MIN_CONF_ID) {
            return null;
        }
        long species = baseConfId != 0L ? baseConfId : fallbackBaseConfId;
        if (species <= MIN_BASE_CONF_ID || height <= 0L || weight <= 0L) {
            return null;
        }
        return new Caught(gid, confId, species, height, weight, voice, voice != 0L, addTime);
    }
}
