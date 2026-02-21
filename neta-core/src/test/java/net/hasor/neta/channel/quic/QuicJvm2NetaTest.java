/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.quic;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.AbstractSoTest;
import org.junit.Test;

/**
 * JVM (raw UDP socket) to Neta QUIC server tests.
 * Simulates a QUIC client using raw DatagramSocket to send
 * properly formed raw-mode QUIC packets to a Neta QUIC server.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicJvm2NetaTest extends AbstractSoTest {
    /**
     * Send a raw Initial QUIC packet from JVM DatagramSocket to Neta QUIC server,
     * then send a STREAM frame with data, verify server receives the data.
     */
    @Test
    public void jvm2Neta_rawInitialAndStream() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<byte[]> serverRcvData = new CopyOnWriteArrayList<>();

        // Neta QUIC server with simple ByteBuf decoder
        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                data.markReader();
                serverRcvData.add(bytes);
            }
            return ProtoStatus.Next;
        }).build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);

        // JVM client: raw DatagramSocket
        DatagramSocket socket = new DatagramSocket();

        // 1. Send raw Initial packet to trigger handshake
        byte[] srcConnId = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        byte[] dstConnId = new byte[] { 0x05, 0x06, 0x07, 0x08 };
        byte[] initialPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QuicConnection.QUIC_VERSION_1, dstConnId, srcConnId, new byte[0], 0, new byte[0]);
        socket.send(new DatagramPacket(initialPacket, initialPacket.length, address));

        // 2. Receive server's Initial ACK response and extract server's SCID
        byte[] rcvBuf = new byte[65535];
        DatagramPacket rcvPkt = new DatagramPacket(rcvBuf, rcvBuf.length);
        socket.setSoTimeout(3000);
        socket.receive(rcvPkt);
        assert rcvPkt.getLength() > 0 : "should receive response from server";

        // Parse server response to get server's source connection ID
        QuicPacket.ParsedPacket serverResponse = QuicPacket.parseRawLongHeaderPacket(rcvBuf, 0, rcvPkt.getLength());
        assert serverResponse != null : "server response should be parseable";
        byte[] serverCid = serverResponse.scid; // server's connection ID

        // 3. Send a STREAM frame with data payload (use server's CID as DCID)
        byte[] message = "Hello from JVM QUIC client".getBytes();
        byte[] streamFrame = buildStreamFrame(0, message);
        byte[] dataPacket = buildRawShortHeaderPacket(serverCid, 1, streamFrame);
        socket.send(new DatagramPacket(dataPacket, dataPacket.length, address));

        // 4. Wait for server to receive and process
        waitFor(() -> !serverRcvData.isEmpty(), 5000);
        assert !serverRcvData.isEmpty() : "server should receive at least 1 message";
        assert new String(serverRcvData.get(0)).equals("Hello from JVM QUIC client") : "data mismatch: " + new String(serverRcvData.get(0));

        socket.close();
        neta.shutdown();
    }

    /**
     * Multiple JVM clients send data to the same Neta QUIC server.
     * Verifies multi-connection handling.
     */
    @Test
    public void jvm2Neta_multipleClients() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<byte[]> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                data.markReader();
                serverRcvData.add(bytes);
            }
            return ProtoStatus.Next;
        }).build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);

        int clientCount = 3;
        DatagramSocket[] sockets = new DatagramSocket[clientCount];

        for (int c = 0; c < clientCount; c++) {
            sockets[c] = new DatagramSocket();
            byte[] srcConnId = new byte[] { (byte) (0x10 + c), 0x02, 0x03, 0x04 };
            byte[] dstConnId = new byte[] { (byte) (0x50 + c), 0x06, 0x07, 0x08 };

            // Send Initial
            byte[] initialPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QuicConnection.QUIC_VERSION_1, dstConnId, srcConnId, new byte[0], 0, new byte[0]);
            sockets[c].send(new DatagramPacket(initialPacket, initialPacket.length, address));

            // Receive server ACK and extract server CID
            byte[] rcvBuf = new byte[65535];
            DatagramPacket rcvPkt = new DatagramPacket(rcvBuf, rcvBuf.length);
            sockets[c].setSoTimeout(3000);
            sockets[c].receive(rcvPkt);

            QuicPacket.ParsedPacket serverResp = QuicPacket.parseRawLongHeaderPacket(rcvBuf, 0, rcvPkt.getLength());
            assert serverResp != null : "server response should be parseable";
            byte[] serverCid = serverResp.scid;

            // Send STREAM data (use server's CID as DCID)
            byte[] message = ("Client-" + c).getBytes();
            byte[] streamFrame = buildStreamFrame(0, message);
            byte[] dataPacket = buildRawShortHeaderPacket(serverCid, 1, streamFrame);
            sockets[c].send(new DatagramPacket(dataPacket, dataPacket.length, address));
        }

        waitFor(() -> serverRcvData.size() >= clientCount, 5000);
        assert serverRcvData.size() >= clientCount : "expected " + clientCount + " messages, got: " + serverRcvData.size();

        for (DatagramSocket s : sockets) {
            s.close();
        }
        neta.shutdown();
    }

    // ── Raw packet building helpers ────────────────────────────────────

    /** Build a STREAM frame (type=0x0A: STREAM + LEN bit) */
    static byte[] buildStreamFrame(long streamId, byte[] payload) {
        int frameType = QuicFrameType.STREAM_BASE | QuicFrameType.STREAM_LEN_BIT;
        byte[] typeBytes = QuicVarInt.encode(frameType);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] lenBytes = QuicVarInt.encode(payload.length);

        byte[] frame = new byte[typeBytes.length + sidBytes.length + lenBytes.length + payload.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
        pos += lenBytes.length;
        System.arraycopy(payload, 0, frame, pos, payload.length);
        return frame;
    }

    /** Build a raw (unencrypted) short header packet. */
    static byte[] buildRawShortHeaderPacket(byte[] dcid, long pn, byte[] payload) {
        int dcidLen = (dcid != null) ? dcid.length : 0;
        int pnLen = 1;
        int headerLen = 1 + dcidLen + pnLen;
        byte[] packet = new byte[headerLen + payload.length];
        packet[0] = (byte) (0x40 | (pnLen - 1)); // short header, fixed bit
        if (dcid != null) {
            System.arraycopy(dcid, 0, packet, 1, dcidLen);
        }
        packet[1 + dcidLen] = (byte) (pn & 0xFF);
        System.arraycopy(payload, 0, packet, headerLen, payload.length);
        return packet;
    }
}
