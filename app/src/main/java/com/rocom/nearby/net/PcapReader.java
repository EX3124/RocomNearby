package com.rocom.nearby.net;

/**
 * Classic pcap stream reader (what PCAPdroid's TCP exporter sends).
 *
 * <p>Supports the four standard pcap magics, little/big endian record headers,
 * microsecond/nanosecond timestamps and Ethernet / raw-IP / Linux-SLL link
 * types. Packet records are passed to the listener without any protocol
 * interpretation.</p>
 */
final class PcapReader {
    interface Listener {
        void onPacket(double timestamp, byte[] data, int offset, int length);
    }

    private static final int MAX_RECORD = 16 * 1024 * 1024;
    private static final int PCAP_MAGIC_BE = 0xA1B2C3D4;
    private static final int PCAP_MAGIC_LE = 0xD4C3B2A1;
    private static final int PCAP_NANO_MAGIC_BE = 0xA1B23C4D;
    private static final int PCAP_NANO_MAGIC_LE = 0x4D3CB2A1;

    private final Listener listener;
    private byte[] buffer = new byte[64 * 1024];
    private int length;
    private boolean headerParsed;
    private boolean littleEndian = true;
    private long timestampDivisor = 1_000_000L;
    private int linkType = 1;

    PcapReader(Listener listener) {
        this.listener = listener;
    }

    int getLinkType() {
        return linkType;
    }

    void reset() {
        length = 0;
        headerParsed = false;
        littleEndian = true;
        timestampDivisor = 1_000_000L;
        linkType = 1;
    }

    void feed(byte[] data, int offset, int count) {
        if (data == null || count <= 0) {
            return;
        }
        ensureCapacity(length + count);
        System.arraycopy(data, offset, buffer, length, count);
        length += count;

        if (!headerParsed) {
            if (length < 24) {
                return;
            }
            parseHeader();
        } else if (length >= 24 && isPcapMagic(readIntBE(buffer, 0))) {
            // PCAPdroid may reconnect and send a fresh global header. The
            // capture service normally removes it, but accepting it here keeps
            // the reader resilient when fed directly.
            parseHeader();
        }
        parseRecords();
    }

    private void parseHeader() {
        int magic = readIntBE(buffer, 0);
        switch (magic) {
            case PCAP_MAGIC_LE:
                littleEndian = true;
                timestampDivisor = 1_000_000L;
                break;
            case PCAP_NANO_MAGIC_BE:
                littleEndian = false;
                timestampDivisor = 1_000_000_000L;
                break;
            case PCAP_NANO_MAGIC_LE:
                littleEndian = true;
                timestampDivisor = 1_000_000_000L;
                break;
            case PCAP_MAGIC_BE:
                littleEndian = false;
                timestampDivisor = 1_000_000L;
                break;
            default:
                // Match the reference client's fallback for unknown magic.
                littleEndian = true;
                timestampDivisor = 1_000_000L;
                break;
        }
        linkType = littleEndian ? readIntLE(buffer, 20) : readIntBE(buffer, 20);
        consume(24);
        headerParsed = true;
    }

    private void parseRecords() {
        while (length >= 16) {
            if (isPcapMagic(readIntBE(buffer, 0))) {
                if (length < 24) {
                    return;
                }
                parseHeader();
                continue;
            }
            long tsSec = readUnsigned(buffer, 0);
            long tsFrac = readUnsigned(buffer, 4);
            long included = readUnsigned(buffer, 8);
            if (included <= 0 || included > MAX_RECORD) {
                consume(1);
                continue;
            }
            int recordLength = (int) included;
            if (length < 16 + recordLength) {
                return;
            }
            double timestamp = tsSec + (double) tsFrac / timestampDivisor;
            listener.onPacket(timestamp, buffer, 16, recordLength);
            consume(16 + recordLength);
        }
    }

    private void ensureCapacity(int needed) {
        if (needed <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        byte[] next = new byte[capacity];
        System.arraycopy(buffer, 0, next, 0, length);
        buffer = next;
    }

    private void consume(int count) {
        if (count <= 0) {
            return;
        }
        if (count >= length) {
            length = 0;
            return;
        }
        System.arraycopy(buffer, count, buffer, 0, length - count);
        length -= count;
    }

    private static boolean isPcapMagic(int magic) {
        return magic == PCAP_MAGIC_BE || magic == PCAP_MAGIC_LE
                || magic == PCAP_NANO_MAGIC_BE || magic == PCAP_NANO_MAGIC_LE;
    }

    private long readUnsigned(byte[] data, int offset) {
        return littleEndian ? readIntLE(data, offset) & 0xFFFFFFFFL
                : readIntBE(data, offset) & 0xFFFFFFFFL;
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
