package com.rocom.nearby.capture;

final class PcapRecordCounter {

    private static final int GLOBAL_HEADER_SIZE = 24;
    private static final int RECORD_HEADER_SIZE = 16;
    private static final int MAX_RECORD_SIZE = 16 * 1024 * 1024;
    private final byte[] buffer = new byte[GLOBAL_HEADER_SIZE];
    private boolean globalHeaderParsed;
    private boolean littleEndian;
    private boolean unsupported;
    private int bufferSize;
    private int packets;
    private int recordHeaderSize;
    private long payloadRemaining;
    int packets() {
        return packets;
    }

    void feed(byte[] data, int offset, int length) {
        if (unsupported || length <= 0) {
            return;
        }
        int position = offset;
        int end = offset + length;
        if (!globalHeaderParsed) {
            int needed = GLOBAL_HEADER_SIZE - bufferSize;
            int take = Math.min(needed, end - position);
            System.arraycopy(data, position, buffer, bufferSize, take);
            bufferSize += take;
            position += take;
            if (bufferSize < GLOBAL_HEADER_SIZE) {
                return;
            }
            if (!parseGlobalHeader()) {
                unsupported = true;
                return;
            }
            globalHeaderParsed = true;
        }
        while (position < end && !unsupported) {
            if (payloadRemaining > 0) {
                int take = (int) Math.min(payloadRemaining, end - position);
                payloadRemaining -= take;
                position += take;
                if (payloadRemaining == 0) {
                    packets++;
                }
                continue;
            }
            int needed = RECORD_HEADER_SIZE - recordHeaderSize;
            int take = Math.min(needed, end - position);
            System.arraycopy(data, position, buffer, recordHeaderSize, take);
            recordHeaderSize += take;
            position += take;
            if (recordHeaderSize < RECORD_HEADER_SIZE) {
                continue;
            }
            long capturedLength = readU32(buffer, 8);
            recordHeaderSize = 0;
            if (capturedLength < 0 || capturedLength > MAX_RECORD_SIZE) {
                unsupported = true;
                break;
            }
            payloadRemaining = capturedLength;
            if (payloadRemaining == 0) {
                packets++;
            }
        }
    }

    private boolean parseGlobalHeader() {
        int magic = readIntBigEndian(buffer, 0);
        if (magic == 0xa1b2c3d4 || magic == 0xa1b23c4d) {
            littleEndian = false;
            return true;
        }
        if (magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1) {
            littleEndian = true;
            return true;
        }
        return false;
    }

    private long readU32(byte[] data, int offset) {
        if (littleEndian) {
            return (data[offset] & 0xffL)
                    | ((data[offset + 1] & 0xffL) << 8)
                    | ((data[offset + 2] & 0xffL) << 16)
                    | ((data[offset + 3] & 0xffL) << 24);
        }
        return ((data[offset] & 0xffL) << 24)
                | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8)
                | (data[offset + 3] & 0xffL);
    }

    private static int readIntBigEndian(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
