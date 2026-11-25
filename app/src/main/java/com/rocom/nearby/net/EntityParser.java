package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class EntityParser {

    static final int MIN_CFG_ID = 10000;
    static final int MAX_CFG_ID = 999999;
    private static final long MIN_TOKEN = 4294967296L;
    private static final long ABSENT = Long.MIN_VALUE;
    private static final int MAX_MOVES = 256;

    static final class Move {

        final String tokenHex;
        final long token;
        final double x;
        final double y;
        final double z;

        Move(String tokenHex, long token, double x, double y, double z) {
            this.tokenHex = tokenHex;
            this.token = token;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static List<EntityRecord> parseSpawn(byte[] plain) {
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return Collections.emptyList();
        List<EntityRecord> out = new ArrayList<>();
        List<ProtoScan.Field> fields = ProtoScan.scan(plain, start, plain.length);
        for (int i = 0; i < fields.size(); i++) {
            ProtoScan.Field field = fields.get(i);
            if (field.number == 7 && field.wire == ProtoScan.WIRE_LENGTH) {
                EntityRecord record = parseEntity(plain, field.valueStart, field.valueEnd);
                if (record != null)
                    out.add(record);
            }
        }
        return out;
    }

    static List<EntityRecord> parseBatch(byte[] plain) {
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return Collections.emptyList();
        List<EntityRecord> out = new ArrayList<>();
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        for (int i = 0; i < top.size(); i++) {
            ProtoScan.Field wrapper = top.get(i);
            if (wrapper.number != 1 || wrapper.wire != ProtoScan.WIRE_LENGTH)
                continue;
            List<ProtoScan.Field> buckets = ProtoScan.scan(plain, wrapper.valueStart, wrapper.valueEnd);
            for (int j = 0; j < buckets.size(); j++) {
                ProtoScan.Field bucket = buckets.get(j);
                if (bucket.wire != ProtoScan.WIRE_LENGTH)
                    continue;
                List<ProtoScan.Field> holders = ProtoScan.scan(plain, bucket.valueStart, bucket.valueEnd);
                for (int k = 0; k < holders.size(); k++) {
                    ProtoScan.Field holder = holders.get(k);
                    if (holder.number != 1 || holder.wire != ProtoScan.WIRE_LENGTH)
                        continue;
                    EntityRecord record = parseEntity(plain, holder.valueStart, holder.valueEnd);
                    if (record != null)
                        out.add(record);
                }
            }
        }
        return out;
    }

    static List<Move> parseMoves(byte[] plain) {
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return Collections.emptyList();
        List<Move> moves = new ArrayList<>();
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        for (int i = 0; i < top.size() && moves.size() < MAX_MOVES; i++) {
            ProtoScan.Field outer = top.get(i);
            if (outer.wire != ProtoScan.WIRE_LENGTH)
                continue;
            List<ProtoScan.Field> middle = ProtoScan.scan(plain, outer.valueStart, outer.valueEnd);
            for (int j = 0; j < middle.size() && moves.size() < MAX_MOVES; j++) {
                ProtoScan.Field inner = middle.get(j);
                if (inner.wire != ProtoScan.WIRE_LENGTH)
                    continue;
                List<ProtoScan.Field> body = ProtoScan.scan(plain, inner.valueStart, inner.valueEnd);
                if (body.size() < 3 || body.size() > 40)
                    continue;
                ProtoScan.Field tokenField = ProtoScan.first(body, 1, ProtoScan.WIRE_VARINT);
                if (tokenField == null)
                    continue;
                if (tokenField.varint < MIN_TOKEN)
                    continue;
                ProtoScan.Field positionField = ProtoScan.first(body, 3, ProtoScan.WIRE_LENGTH);
                if (positionField == null)
                    continue;
                double[] position = ProtoScan.strictPosition(plain, positionField.valueStart, positionField.valueEnd);
                if (position == null)
                    continue;
                moves.add(new Move(Long.toHexString(tokenField.varint), tokenField.varint, position[0], position[1], position[2]));
            }
        }
        return moves;
    }

    static EntityRecord parseEntity(byte[] buf, int start, int end) {
        List<ProtoScan.Field> fields = ProtoScan.scan(buf, start, end);
        int containerStart = start;
        int containerEnd = end;
        ProtoScan.Field wrapper = ProtoScan.first(fields, 11, ProtoScan.WIRE_LENGTH);
        if (wrapper != null) {
            containerStart = wrapper.valueStart;
            containerEnd = wrapper.valueEnd;
            fields = ProtoScan.scan(buf, containerStart, containerEnd);
        }
        ProtoScan.Field data = ProtoScan.first(fields, 3, ProtoScan.WIRE_LENGTH);
        if (data == null)
            return null;
        ProtoScan.Field actorInfo = ProtoScan.first(fields, 1, ProtoScan.WIRE_LENGTH);
        ProtoScan.Field positionContainer = ProtoScan.firstChild(buf, actorInfo, 8);
        ProtoScan.Field coordinates = ProtoScan.firstChild(buf, positionContainer, 1);
        long actorFromInfo = actorInfo == null ? ABSENT : ProtoScan.varintValue(buf, actorInfo.valueStart, actorInfo.valueEnd, 1, ABSENT);
        long actorFromMessage = ProtoScan.varintValue(buf, containerStart, containerEnd, 1, ABSENT);
        long actorId = actorFromInfo != ABSENT ? actorFromInfo : actorFromMessage != ABSENT ? actorFromMessage : 0L;
        long level = actorInfo == null ? 0L : ProtoScan.varintValue(buf, actorInfo.valueStart, actorInfo.valueEnd, 11, 0L);
        long token = actorInfo == null ? 0L : ProtoScan.varintValue(buf, actorInfo.valueStart, actorInfo.valueEnd, 2, 0L);
        ProtoScan.Field glass = ProtoScan.firstChild(buf, data, 30);
        long glassType = glass == null ? 0L : ProtoScan.varintValue(buf, glass.valueStart, glass.valueEnd, 1, 0L);
        long glassValue = glass == null ? 0L : ProtoScan.varintValue(buf, glass.valueStart, glass.valueEnd, 2, 0L);
        long cfgId = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 1, 0L);
        long refreshId = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 10, 0L);
        long uniqueId = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 17, 0L);
        long height = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 11, 0L);
        long weight = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 12, 0L);
        long mutation = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 14, 0L);
        long voice = ProtoScan.varintValue(buf, data.valueStart, data.valueEnd, 31, EntityRecord.NO_VOICE);
        double x = 0;
        double y = 0;
        double z = 0;
        if (coordinates != null) {
            double[] position = ProtoScan.position(buf, coordinates.valueStart, coordinates.valueEnd);
            if (position != null) {
                x = position[0];
                y = position[1];
                z = position[2];
            }
        }
        return new EntityRecord(actorId, token, cfgId, refreshId, uniqueId, level, height, weight, mutation, voice, glassType, glassValue, x, y, z);
    }

    static List<Long> parseDeparted(byte[] plain) {
        if (plain == null || plain.length < 16)
            return Collections.emptyList();
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        for (int i = 0; i < top.size(); i++) {
            ProtoScan.Field wrapper = top.get(i);
            if (wrapper.number != 1 || wrapper.wire != ProtoScan.WIRE_LENGTH)
                continue;
            List<ProtoScan.Field> children = ProtoScan.scan(plain, wrapper.valueStart, wrapper.valueEnd);
            for (int j = 0; j < children.size(); j++) {
                ProtoScan.Field child = children.get(j);
                if (child.wire != ProtoScan.WIRE_LENGTH)
                    continue;
                List<ProtoScan.Field> body = ProtoScan.scan(plain, child.valueStart, child.valueEnd);
                if (body.size() != 1 || body.get(0).number != 1 || body.get(0).wire != ProtoScan.WIRE_VARINT)
                    continue;
                long token = body.get(0).varint;
                if (token >= MIN_TOKEN && token != (token & 0xFFFFFFFFFFFFL))
                    out.add(token);
            }
        }
        return out;
    }
}
