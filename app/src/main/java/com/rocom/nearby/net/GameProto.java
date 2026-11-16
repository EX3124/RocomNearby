package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal protobuf scanner for the map-relevant game opcodes.
 *
 * <p>The reference client does not use a schema; it scans for length-delimited
 * fields and validates candidate x/y/z messages by range. These helpers mirror
 * that approach for opcodes 307 (self move), 348 (teleport) and 338 (enter
 * scene).</p>
 */
final class GameProto {
    static final class Position {
        final double x;
        final double y;
        final double z;

        Position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class Teleport {
        final int sceneId;
        final Position position;

        Teleport(int sceneId, Position position) {
            this.sceneId = sceneId;
            this.position = position;
        }
    }

    private static final class Field {
        final int number;
        final int wireType;
        final long value;
        final byte[] bytes;

        Field(int number, int wireType, long value, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.value = value;
            this.bytes = bytes;
        }
    }

    private static final class Scan {
        final List<Field> fields;
        final int consumed;

        Scan(List<Field> fields, int consumed) {
            this.fields = fields;
            this.consumed = consumed;
        }
    }

    private static final class FoundPosition {
        final Position position;
        final int pathLength;

        FoundPosition(Position position, int pathLength) {
            this.position = position;
            this.pathLength = pathLength;
        }
    }

    private GameProto() {
    }

    static Position parseSelfMove(byte[] plaintext) {
        if (plaintext == null || plaintext.length < 16) {
            return null;
        }
        int limit = Math.min(24, plaintext.length - 4);
        Position best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int start = 8; start <= limit; start++) {
            Scan scan = scan(plaintext, start, plaintext.length, 20000);
            if (scan.fields.size() < 3) {
                continue;
            }
            FoundPosition found = findPosition(plaintext, start, 1);
            if (found == null) {
                continue;
            }
            int score = 100 * scan.consumed - found.pathLength;
            if (score > bestScore) {
                bestScore = score;
                best = found.position;
            }
        }
        return best;
    }

    static Teleport parseTeleport(byte[] plaintext) {
        if (plaintext == null || plaintext.length < 14) {
            return null;
        }
        int base = 10;
        Teleport best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int extra = 0; extra <= 16 && base + extra < plaintext.length; extra++) {
            Scan scan = scan(plaintext, base + extra, plaintext.length, 300);
            if (scan.fields.isEmpty()) {
                continue;
            }
            Map<Integer, Long> values = new HashMap<>();
            Field positionField = null;
            int presentFields = 0;
            for (Field field : scan.fields) {
                presentFields++;
                if (field.wireType == 0) {
                    values.put(field.number, field.value);
                } else if (field.wireType == 2 && field.number == 14) {
                    positionField = field;
                }
            }
            Long sceneValue = values.get(12);
            if (sceneValue == null || sceneValue < 10000 || sceneValue > 9999999) {
                continue;
            }
            Position position = positionField != null
                    ? parseTeleportPosition(positionField.bytes) : null;
            if (position == null) {
                continue;
            }
            int score = presentFields + 20;
            if (score > bestScore) {
                bestScore = score;
                best = new Teleport(sceneValue.intValue(), position);
            }
        }
        return best;
    }

    static int parseEnterScene(byte[] plaintext) {
        if (plaintext == null || plaintext.length < 14) {
            return 0;
        }
        int base = 10;
        int bestScene = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int extra = 0; extra <= 16 && base + extra < plaintext.length; extra++) {
            Scan scan = scan(plaintext, base + extra, plaintext.length, 300);
            if (scan.fields.isEmpty()) {
                continue;
            }
            int sceneId = 0;
            boolean hasCfg = false;
            for (Field field : scan.fields) {
                if (field.wireType != 0) {
                    continue;
                }
                if (field.number == 3) {
                    sceneId = (int) field.value;
                } else if (field.number == 2) {
                    hasCfg = true;
                }
            }
            if (sceneId < 10000 || sceneId > 9999999) {
                continue;
            }
            int score = scan.fields.size() + (hasCfg ? 20 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestScene = sceneId;
            }
        }
        return bestScene;
    }

    private static FoundPosition findPosition(byte[] data, int offset, int depth) {
        Scan scan = scan(data, offset, data.length, 20000);
        for (Field field : scan.fields) {
            if (field.wireType != 2) {
                continue;
            }
            Position direct = parseCoordinate(field.bytes, 100000, true);
            if (direct != null) {
                return new FoundPosition(direct, 1);
            }
        }
        if (depth >= 2) {
            return null;
        }
        for (Field field : scan.fields) {
            if (field.wireType != 2) {
                continue;
            }
            FoundPosition nested = findPosition(field.bytes, 0, depth + 1);
            if (nested != null) {
                return new FoundPosition(nested.position, nested.pathLength + 1);
            }
        }
        return null;
    }

    private static Position parseTeleportPosition(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Scan scan = scan(data, 0, data.length, 300);
        for (Field field : scan.fields) {
            if (field.wireType == 2 && field.number == 1) {
                Position position = parseCoordinate(field.bytes, 1000, false);
                if (position != null) {
                    return position;
                }
            }
        }
        return parseCoordinate(data, 1000, false);
    }

    /**
     * Parses 1/2/3 varint fields as x/y/z. {@code exactLength} mirrors the
     * reference {@code va()} requirement (all bytes consumed by fields).
     */
    private static Position parseCoordinate(byte[] data, int minAbs, boolean exactLength) {
        if (data == null || data.length < 2) {
            return null;
        }
        Scan scan = scan(data, 0, data.length, 20000);
        if (scan.fields.isEmpty()) {
            return null;
        }
        if (exactLength && scan.consumed != data.length) {
            return null;
        }
        Long x = null;
        Long y = null;
        long z = 0;
        for (Field field : scan.fields) {
            if (field.wireType != 0) {
                return null;
            }
            if (field.number == 1) {
                x = field.value;
            } else if (field.number == 2) {
                y = field.value;
            } else if (field.number == 3) {
                z = field.value;
            }
        }
        if (x == null || y == null) {
            return null;
        }
        double dx = signed(x);
        double dy = signed(y);
        if (Math.abs(dx) < minAbs || Math.abs(dx) > 2000000
                || Math.abs(dy) < minAbs || Math.abs(dy) > 2000000) {
            return null;
        }
        return new Position(dx, dy, signed(z));
    }

    private static Scan scan(byte[] data, int offset, int end, int maxFieldNumber) {
        List<Field> fields = new ArrayList<>();
        if (data == null || offset < 0 || end > data.length || offset >= end) {
            return new Scan(fields, 0);
        }
        int position = offset;
        int count = 0;
        while (position < end && count < 400) {
            count++;
            long[] tag = readVarint(data, position, end);
            if (tag == null) {
                break;
            }
            int tagValue = (int) tag[0];
            int fieldNumber = tagValue >>> 3;
            int wireType = tagValue & 7;
            if (fieldNumber <= 0 || fieldNumber > maxFieldNumber
                    || wireType == 3 || wireType == 4 || wireType > 5) {
                break;
            }
            position = (int) tag[1];
            if (wireType == 0) {
                long[] value = readVarint(data, position, end);
                if (value == null) {
                    break;
                }
                fields.add(new Field(fieldNumber, wireType, value[0], null));
                position = (int) value[1];
            } else if (wireType == 1) {
                if (position + 8 > end) {
                    break;
                }
                fields.add(new Field(fieldNumber, wireType, 0, null));
                position += 8;
            } else if (wireType == 5) {
                if (position + 4 > end) {
                    break;
                }
                fields.add(new Field(fieldNumber, wireType, 0, null));
                position += 4;
            } else {
                long[] lengthValue = readVarint(data, position, end);
                if (lengthValue == null) {
                    break;
                }
                int length = (int) lengthValue[0];
                int start = (int) lengthValue[1];
                if (length < 0 || start + length > end) {
                    break;
                }
                byte[] bytes = new byte[length];
                System.arraycopy(data, start, bytes, 0, length);
                fields.add(new Field(fieldNumber, wireType, 0, bytes));
                position = start + length;
            }
        }
        return new Scan(fields, position - offset);
    }

    private static long[] readVarint(byte[] data, int offset, int end) {
        long value = 0;
        int shift = 0;
        int position = offset;
        while (position < end && shift < 64) {
            int b = data[position++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{value, position};
            }
            shift += 7;
        }
        return null;
    }

    private static double signed(long value) {
        // readVarint already accumulates the 64-bit two's-complement value, so
        // negative coordinates are already signed in the long.
        return (double) value;
    }
}
