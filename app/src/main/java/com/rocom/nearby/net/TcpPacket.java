package com.rocom.nearby.net;

final class TcpPacket {

    final double timestamp;
    final int srcIp;
    final int dstIp;
    final int srcPort;
    final int dstPort;
    final long sequence;
    final int flags;
    final boolean syn;
    final byte[] payload;

    TcpPacket(double timestamp, int srcIp, int dstIp, int srcPort, int dstPort, long sequence, int flags, byte[] payload) {
        this.timestamp = timestamp;
        this.srcIp = srcIp;
        this.dstIp = dstIp;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.sequence = sequence;
        this.flags = flags;
        this.syn = (flags & 0x02) != 0;
        this.payload = payload;
    }
}
