package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.List;

final class ProtoScan {

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LENGTH = 2;
    static final int WIRE_FIXED32 = 5;
    private static final int MAX_FIELDS = 400;
    private static final int MIN_START = 8;
    private static final int MAX_START = 20;

    static final class Field {

        final int number;
        final int wire;
        final long varint;
        final int valueStart;
        final int valueEnd;
        final int end;

        Field(int number, int wire, long varint, int valueStart, int valueEnd, int end) {
            this.number = number;
            this.wire = wire;
            this.varint = varint;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
            this.end = end;
        }
    }
    
    static int readVarint(byte[] buf, int offset, int end, long[] out) {
        long value = 0L;
        int shift = 0;
        int pos = offset;
        while (pos < end && shift <= 63) {
            int current = buf[pos] & 0xFF;
            pos++;
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                out[0] = value;
                return pos;
            }
            shift += 7;
        }
        return -1;
    }

    static List<Field> scan(byte[] buf, int start, int end) {
        List<Field> fields = new ArrayList<>();
        if (buf == null || start < 0 || end > buf.length || start >= end) {
            return fields;
        }
        long[] value = new long[1];
        int pos = start;
        int guard = 0;
        while (pos < end && guard < MAX_FIELDS) {
            guard++;
            int afterTag = readVarint(buf, pos, end, value);
            if (afterTag < 0) {
                break;
            }
            int number = (int) (value[0] >>> 3);
            int wire = (int) (value[0] & 7);
            if (number <= 0 || number > 20000
                    || wire == 3 || wire == 4 || wire > WIRE_FIXED32) {
                break;
            }
            if (wire == WIRE_VARINT) {
                int afterValue = readVarint(buf, afterTag, end, value);
                if (afterValue < 0) {
                    break;
                }
                fields.add(new Field(number, wire, value[0], afterTag, afterValue, afterValue));
                pos = afterValue;
                continue;
            }
            if (wire == WIRE_LENGTH) {
                int afterLength = readVarint(buf, afterTag, end, value);
                if (afterLength < 0) {
                    break;
                }
                long length = value[0];
                if (length < 0 || length > end - afterLength) {
                    break;
                }
                int payloadEnd = afterLength + (int) length;
                fields.add(new Field(number, wire, 0L, afterLength, payloadEnd, payloadEnd));
                pos = payloadEnd;
                continue;
            }
            int payload = wire == WIRE_FIXED64 ? 8 : 4;
            if (afterTag + payload > end) {
                break;
            }
            fields.add(new Field(number, wire, 0L, afterTag, afterTag + payload,
                    afterTag + payload));
            pos = afterTag + payload;
        }
        return fields;
    }

    static Field first(List<Field> fields, int number, int wire) {
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field.number == number && field.wire == wire) {
                return field;
            }
        }
        return null;
    }
    
    static Field firstChild(byte[] buf, Field parent, int number) {
        if (parent == null || parent.wire != WIRE_LENGTH) {
            return null;
        }
        return first(scan(buf, parent.valueStart, parent.valueEnd), number, WIRE_LENGTH);
    }

    static long varintValue(byte[] buf, int start, int end, int number, long fallback) {
        if (buf == null || start < 0 || end > buf.length) {
            return fallback;
        }
        long[] value = new long[1];
        int pos = start;
        int guard = 0;
        while (pos < end && guard < MAX_FIELDS) {
            guard++;
            int afterTag = readVarint(buf, pos, end, value);
            if (afterTag < 0) {
                return fallback;
            }
            int field = (int) (value[0] >>> 3);
            int wire = (int) (value[0] & 7);
            if (field <= 0) {
                return fallback;
            }
            if (wire == WIRE_VARINT) {
                int afterValue = readVarint(buf, afterTag, end, value);
                if (afterValue < 0) {
                    return fallback;
                }
                if (field == number) {
                    return value[0];
                }
                pos = afterValue;
                continue;
            }
            if (wire == WIRE_LENGTH) {
                int afterLength = readVarint(buf, afterTag, end, value);
                if (afterLength < 0 || value[0] < 0 || value[0] > end - afterLength) {
                    return fallback;
                }
                pos = afterLength + (int) value[0];
                continue;
            }
            if (wire == WIRE_FIXED64) {
                pos = afterTag + 8;
                continue;
            }
            if (wire == WIRE_FIXED32) {
                pos = afterTag + 4;
                continue;
            }
            return fallback;
        }
        return fallback;
    }

    static int findMessageStart(byte[] buf) {
        if (buf == null || buf.length < 4) {
            return -1;
        }
        int limit = Math.min(MAX_START, buf.length - 1);
        int bestStart = -1;
        int bestConsumed = 0;
        int bestCount = 0;
        for (int start = MIN_START; start <= limit; start++) {
            List<Field> fields = scan(buf, start, buf.length);
            if (fields.isEmpty()) {
                continue;
            }
            int consumed = fields.get(fields.size() - 1).end - start;
            int count = fields.size();
            boolean better = consumed > bestConsumed
                    || (consumed == bestConsumed && count > bestCount)
                    || (consumed == bestConsumed && count == bestCount && start > bestStart);
            if (better) {
                bestStart = start;
                bestConsumed = consumed;
                bestCount = count;
            }
        }
        return bestCount <= 0 ? -1 : bestStart;
    }

    static double[] position(byte[] buf, int start, int end) {
        List<Field> fields = scan(buf, start, end);
        if (fields.isEmpty()) {
            return null;
        }
        boolean hasX = false;
        boolean hasY = false;
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field.wire != WIRE_VARINT) {
                continue;
            }
            if (field.number == 1) {
                x = field.varint;
                hasX = true;
            } else if (field.number == 2) {
                y = field.varint;
                hasY = true;
            } else if (field.number == 3) {
                z = field.varint;
            }
        }
        return hasX && hasY ? new double[]{x, y, z} : null;
    }

    static double[] strictPosition(byte[] buf, int start, int end) {
        List<Field> fields = scan(buf, start, end);
        if (fields.isEmpty() || fields.size() > 3) {
            return null;
        }
        if (fields.get(fields.size() - 1).end != end) {
            return null;
        }
        boolean hasX = false;
        boolean hasY = false;
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field.wire != WIRE_VARINT) {
                return null;
            }
            if (field.number == 1) {
                x = field.varint;
                hasX = true;
            } else if (field.number == 2) {
                y = field.varint;
                hasY = true;
            } else if (field.number == 3) {
                z = field.varint;
            } else {
                return null;
            }
        }
        if (!hasX || !hasY) {
            return null;
        }
        if (!isWorldCoordinate(x) || !isWorldCoordinate(y)) {
            return null;
        }
        return new double[]{x, y, z};
    }

    static boolean isWorldCoordinate(double value) {
        double magnitude = Math.abs(value);
        return magnitude >= 100000 && magnitude <= 2000000;
    }
}
