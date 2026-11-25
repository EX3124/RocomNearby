package com.rocom.nearby.net;

import com.rocom.nearby.entity.NearbyEntity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NearbyPacketPipeline {

    public interface Listener {
        void onMapPosition(int sceneId, double x, double y);
        void onSceneChanged(int sceneId);
        void onEntities(List<NearbyEntity> entities);
    }

    private static final int GAME_PORT = 8195;
    private final Listener listener;
    private final PcapReader pcapReader;
    private final Map<String, TcpFlow> flows = new HashMap<>();

    public NearbyPacketPipeline(Listener listener) {
        this.listener = listener;
        this.pcapReader = new PcapReader(this::onPcapRecord);
    }

    public void feed(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        try {
            pcapReader.feed(data, offset, length);
        } catch (Throwable ignored) {}
    }

    public void reset() {
        pcapReader.reset();
        flows.clear();
    }

    private void onPcapRecord(double timestamp, byte[] data, int offset, int length) {
        int linkType = pcapReader.getLinkType();
        int ipStart = resolveIpStart(linkType, data, offset, length);
        if (ipStart < 0) return;
        int ipEnd = offset + length;
        if (ipStart + 20 > ipEnd) return;
        int version = (data[ipStart] >> 4) & 0x0F;
        if (version != 4) return;
        int ipHeaderLength = (data[ipStart] & 0x0F) * 4;
        if (ipHeaderLength < 20) return;
        int totalLength = readU16BE(data, ipStart + 2);
        if (totalLength < ipHeaderLength + 20) return;
        int packetEnd = Math.min(ipStart + totalLength, ipEnd);
        int protocol = data[ipStart + 9] & 0xFF;
        if (protocol != 6) return;
        int fragment = readU16BE(data, ipStart + 6);
        if ((fragment & 0x3FFF) != 0) return;
        int srcIp = readIntBE(data, ipStart + 12);
        int dstIp = readIntBE(data, ipStart + 16);
        int tcpStart = ipStart + ipHeaderLength;
        if (tcpStart + 20 > packetEnd) return;
        int srcPort = readU16BE(data, tcpStart);
        int dstPort = readU16BE(data, tcpStart + 2);
        if (srcPort != GAME_PORT && dstPort != GAME_PORT) return;
        int tcpHeaderLength = ((data[tcpStart + 12] >> 4) & 0x0F) * 4;
        if (tcpHeaderLength < 20) return;
        int payloadStart = tcpStart + tcpHeaderLength;
        if (payloadStart > packetEnd) return;
        long sequence = readU32BE(data, tcpStart + 4);
        int flags = data[tcpStart + 13] & 0xFF;
        byte[] payload = Arrays.copyOfRange(data, payloadStart, packetEnd);
        TcpPacket packet = new TcpPacket(timestamp, srcIp, dstIp, srcPort, dstPort, sequence, flags, payload);
        handleTcp(packet);
    }

    private void handleTcp(TcpPacket packet) {
        boolean c2s = packet.dstPort == GAME_PORT;
        if (!c2s && packet.srcPort != GAME_PORT) return;
        int clientIp = c2s ? packet.srcIp : packet.dstIp;
        int clientPort = c2s ? packet.srcPort : packet.dstPort;
        int serverIp = c2s ? packet.dstIp : packet.srcIp;
        String key = clientIp + ":" + clientPort + ":" + serverIp;
        TcpFlow flow = flows.get(key);
        if (flow == null || (c2s && packet.syn)) {
            flow = new TcpFlow();
            flows.put(key, flow);
        }
        long sequence = packet.sequence + (packet.syn ? 1 : 0);
        TcpReassembler reassembler = c2s ? flow.c2sTcp : flow.s2cTcp;
        TgcpFramer framer = c2s ? flow.c2sFramer : flow.s2cFramer;
        byte[] stream = reassembler.feed(sequence, packet.payload);
        if (stream.length == 0) return;
        List<TgcpFramer.TgcpFrame> frames = framer.feed(stream);
        String direction = c2s ? "c2s" : "s2c";
        for (TgcpFramer.TgcpFrame frame : frames)
            flow.session.processFrame(direction, frame);
    }

    private static int resolveIpStart(int linkType, byte[] data, int offset, int length) {
        int end = offset + length;
        if (linkType == 1) {
            int ethernet = ethernetIpStart(data, offset, end);
            if (ethernet >= 0)
                return ethernet;
            return length > 0 && ((data[offset] >> 4) & 0x0F) == 4 ? offset : -1;
        }
        if (linkType == 101) {
            if (length > 0 && ((data[offset] >> 4) & 0x0F) == 4)
                return offset;
            return ethernetIpStart(data, offset, end);
        }
        if (linkType == 113) {
            if (length < 16)
                return -1;
            return readU16BE(data, offset + 14) == 0x0800 ? offset + 16 : -1;
        }
        if (linkType == 0 || linkType == 108)
            return length >= 4 ? offset + 4 : -1;
        int ethernet = ethernetIpStart(data, offset, end);
        if (ethernet >= 0)
            return ethernet;
        return length > 0 && ((data[offset] >> 4) & 0x0F) == 4 ? offset : -1;
    }

    private static int ethernetIpStart(byte[] data, int offset, int end) {
        if (offset + 14 > end)
            return -1;
        int etherType = readU16BE(data, offset + 12);
        int ipStart = offset + 14;
        if (etherType == 0x8100) {
            if (offset + 18 > end)
                return -1;
            etherType = readU16BE(data, offset + 16);
            ipStart = offset + 18;
        }
        return etherType == 0x0800 ? ipStart : -1;
    }

    private static int readU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static long readU32BE(byte[] data, int offset) {
        return readIntBE(data, offset) & 0xFFFFFFFFL;
    }

    private final class TcpFlow {

        final TcpReassembler c2sTcp = new TcpReassembler();
        final TcpReassembler s2cTcp = new TcpReassembler();
        final TgcpFramer c2sFramer = new TgcpFramer();
        final TgcpFramer s2cFramer = new TgcpFramer();
        final GameSession session;

        TcpFlow() {
            this.session = new GameSession(new GameSession.Listener() {
                @Override
                public void onMapPosition(int sceneId, double x, double y) {
                    listener.onMapPosition(sceneId, x, y);
                }

                @Override
                public void onSceneChanged(int sceneId) {
                    listener.onSceneChanged(sceneId);
                }

                @Override
                public void onEntities(List<NearbyEntity> entities) {
                    listener.onEntities(entities);
                }
            });
        }
    }
}
