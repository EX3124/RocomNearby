package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.List;

final class TgcpFramer {

    private static final int MAGIC_0 = 0x33;
    private static final int MAGIC_1 = 0x66;
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private byte[] buffer = new byte[8 * 1024];
    private int length;

    List<TgcpFrame> feed(byte[] data) {
        List<TgcpFrame> frames = new ArrayList<>();
        if (data != null && data.length > 0)
            append(data);
        while (length > 0) {
            if (startsWithSixZeros()) {
                consume(6);
                continue;
            }
            int magic = indexOfMagic();
            if (magic < 0) {
                if (buffer[length - 1] == (byte) MAGIC_0) {
                    buffer[0] = (byte) MAGIC_0;
                    length = 1;
                } else length = 0;
                return frames;
            }
            if (magic > 0)
                consume(magic);
            if (length < 21)
                return frames;
            long headerLength = readUIntBE(buffer, 13);
            long bodyLength = readUIntBE(buffer, 17);
            if (headerLength < 21 || headerLength > MAX_FRAME) {
                consume(2);
                continue;
            }
            long total = headerLength + bodyLength;
            if (total < headerLength || total > MAX_FRAME) {
                consume(2);
                continue;
            }
            if (length < total)
                return frames;
            byte[] frame = new byte[(int) total];
            System.arraycopy(buffer, 0, frame, 0, frame.length);
            TgcpFrame parsed = new TgcpFrame(frame, (byte) (buffer[6] & 0xFF), (byte) (buffer[7] & 0xFF), readUIntBE(buffer, 9), (int) headerLength, (int) bodyLength);
            consume((int) total);
            frames.add(parsed);
        }
        return frames;
    }

    private boolean startsWithSixZeros() {
        if (length < 6)
            return false;
        for (int i = 0; i < 6; i++) {
            if (buffer[i] != 0)
                return false;
        }
        return true;
    }

    private int indexOfMagic() {
        for (int i = 0; i + 1 < length; i++) {
            if ((buffer[i] & 0xFF) == MAGIC_0 && (buffer[i + 1] & 0xFF) == MAGIC_1)
                return i;
        }
        return -1;
    }

    private void append(byte[] data) {
        if (length + data.length > buffer.length) {
            int capacity = buffer.length;
            while (capacity < length + data.length)
                capacity *= 2;
            byte[] next = new byte[capacity];
            System.arraycopy(buffer, 0, next, 0, length);
            buffer = next;
        }
        System.arraycopy(data, 0, buffer, length, data.length);
        length += data.length;
    }

    private void consume(int count) {
        if (count >= length) {
            length = 0;
            return;
        }
        System.arraycopy(buffer, count, buffer, 0, length - count);
        length -= count;
    }

    private static long readUIntBE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) | ((long) (data[offset + 1] & 0xFF) << 16) | ((long) (data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    static final class TgcpFrame {

        final byte[] raw;
        final byte kind0;
        final byte kind1;
        final long sequence;
        final int headerLength;
        final int bodyLength;

        TgcpFrame(byte[] raw, byte kind0, byte kind1, long sequence, int headerLength, int bodyLength) {
            this.raw = raw;
            this.kind0 = kind0;
            this.kind1 = kind1;
            this.sequence = sequence;
            this.headerLength = headerLength;
            this.bodyLength = bodyLength;
        }

        int kind() {
            return ((kind0 & 0xFF) << 8) | (kind1 & 0xFF);
        }
    }
}
