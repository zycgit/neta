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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import org.junit.Test;

/**
 * Neta QUIC client to JVM (raw UDP) server tests.
 * A raw DatagramSocket acts as a QUIC server responding with properly
 * formed raw-mode QUIC packets.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicNeta2JvmTest extends AbstractSoTest {

    // Default connection ID length must match QuicSoConfig default (8 bytes)
    private static final int CID_LEN = 8;

    /**
     * Neta QUIC client connects to a JVM DatagramSocket that acts as a raw QUIC server.
     * The JVM server sends back an Initial ACK to complete the QUIC handshake.
     * Then the JVM server sends a STREAM frame with data to the Neta client.
     */
    @Test
    public void neta2Jvm_clientConnect() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicReference<byte[]> clientRcvRef = new AtomicReference<>();
        AtomicBoolean serverReady = new AtomicBoolean(false);

        // JVM raw QUIC server in a background thread
        DatagramSocket serverSocket = new DatagramSocket(address);
        NetManager neta = null;
        try {
            serverSocket.setSoTimeout(10000);
            Thread serverThread = new Thread(() -> {
                try {
                    serverReady.set(true);
                    // 1. Receive client Initial packet
                    byte[] rcvBuf = new byte[65535];
                    DatagramPacket rcvPkt = new DatagramPacket(rcvBuf, rcvBuf.length);
                    serverSocket.receive(rcvPkt);

                    // Parse the Initial packet to get connection IDs
                    byte[] data = new byte[rcvPkt.getLength()];
                    System.arraycopy(rcvPkt.getData(), rcvPkt.getOffset(), data, 0, data.length);

                    QuicPacket.ParsedPacket parsed = QuicPacket.parseRawLongHeaderPacket(data, 0, data.length);
                    if (parsed == null) {
                        return;
                    }

                    // 2. Send back Initial ACK (server CID must be CID_LEN bytes to match client expectations)
                    byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
                    byte[] serverSrcConnId = new byte[CID_LEN];
                    for (int i = 0; i < CID_LEN; i++) {
                        serverSrcConnId[i] = (byte) (0xA0 + i);
                    }
                    byte[] ackPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QuicChannel.QUIC_VERSION_1, parsed.scid, // dst = client's src
                            serverSrcConnId, // src = server's own
                            new byte[0], 0, ackFrame);
                    serverSocket.send(new DatagramPacket(ackPacket, ackPacket.length, rcvPkt.getSocketAddress()));

                    // 3. Send STREAM frame with payload to client
                    byte[] message = "Hello from JVM server".getBytes();
                    byte[] streamFrame = QuicJvm2NetaTest.buildStreamFrame(0, message);
                    // Use client's srcConnId as DCID (this is what the Neta client expects as its srcConnectionId)
                    byte[] dataPacket = QuicJvm2NetaTest.buildRawShortHeaderPacket(parsed.scid, 1, streamFrame);
                    serverSocket.send(new DatagramPacket(dataPacket, dataPacket.length, rcvPkt.getSocketAddress()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Wait for server to be ready
            waitFor(serverReady::get, 3000);

            // Neta QUIC client
            ProtoInitializer clientProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
                while (src.hasMore()) {
                    ByteBuf buf = src.takeMessage();
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    buf.markReader();
                    clientRcvRef.set(bytes);
                }
                return ProtoStatus.Next;
            }).build();

            neta = new NetManager(globalConf());
            QuicSoConfig quicConf = quicConfig();

            Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
            NetChannel clientChannel = connect.get();
            assert clientChannel != null : "client should connect successfully";
            assert !clientChannel.isClose() : "client channel should be open";

            // Wait for client to receive data from server
            waitFor(() -> clientRcvRef.get() != null, 3000);
            byte[] received = clientRcvRef.get();
            assert received != null : "client should receive data from server";
            assert new String(received).equals("Hello from JVM server") : "data mismatch: " + new String(received);

            serverThread.join(3000);
        } finally {
            serverSocket.close();
            if (neta != null) {
                neta.shutdown();
            }
        }
    }

    /**
     * Neta QUIC client sends data to JVM raw server.
     * Verifies the JVM server can read STREAM frame data from Neta client.
     */
    @Test
    public void neta2Jvm_clientSendsData() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicReference<byte[]> serverRcvRef = new AtomicReference<>();
        AtomicBoolean serverReady = new AtomicBoolean(false);

        // JVM raw QUIC server
        DatagramSocket serverSocket = new DatagramSocket(address);
        NetManager neta = null;
        try {
            serverSocket.setSoTimeout(10000);
            Thread serverThread = new Thread(() -> {
                try {
                    serverReady.set(true);
                    // 1. Receive client Initial
                    byte[] rcvBuf = new byte[65535];
                    DatagramPacket rcvPkt = new DatagramPacket(rcvBuf, rcvBuf.length);
                    serverSocket.receive(rcvPkt);

                    byte[] data = new byte[rcvPkt.getLength()];
                    System.arraycopy(rcvPkt.getData(), rcvPkt.getOffset(), data, 0, data.length);
                    QuicPacket.ParsedPacket parsed = QuicPacket.parseRawLongHeaderPacket(data, 0, data.length);

                    // 2. Send back Initial ACK (CID_LEN == 8 bytes to match Neta client's default)
                    byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
                    byte[] serverSrcConnId = new byte[CID_LEN];
                    for (int i = 0; i < CID_LEN; i++) {
                        serverSrcConnId[i] = (byte) (0xB0 + i);
                    }
                    byte[] ackPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QuicChannel.QUIC_VERSION_1, parsed.scid, serverSrcConnId, new byte[0], 0, ackFrame);
                    serverSocket.send(new DatagramPacket(ackPacket, ackPacket.length, rcvPkt.getSocketAddress()));

                    // 3. Receive data packet from Neta client (short header with STREAM frame)
                    DatagramPacket dataPkt = new DatagramPacket(rcvBuf, rcvBuf.length);
                    serverSocket.receive(dataPkt);

                    byte[] pktData = new byte[dataPkt.getLength()];
                    System.arraycopy(dataPkt.getData(), dataPkt.getOffset(), pktData, 0, pktData.length);

                    // Parse short header: the Neta client sends DCID from its initial random dstConnectionId (CID_LEN bytes)
                    int dcidLen = CID_LEN; // must match client's soConfig.getConnectionIdLength()
                    int pnLen = (pktData[0] & 0x03) + 1;
                    int headerLen = 1 + dcidLen + pnLen;

                    if (headerLen < pktData.length) {
                        // Parse STREAM frame from payload
                        byte[] payload = new byte[pktData.length - headerLen];
                        System.arraycopy(pktData, headerLen, payload, 0, payload.length);
                        byte[] streamData = extractStreamData(payload);
                        if (streamData != null) {
                            serverRcvRef.set(streamData);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            waitFor(serverReady::get, 3000);

            // Neta QUIC client
            ProtoInitializer clientProto = ProtoHelper.standard().build();

            neta = new NetManager(globalConf());
            QuicSoConfig quicConf = quicConfig();

            Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
            NetChannel clientChannel = connect.get();
            assert clientChannel != null : "connect should succeed";

            // Send data
            clientChannel.sendData(ByteBuf.wrap("Hello from Neta client".getBytes()));

            // Wait for JVM server to receive
            waitFor(() -> serverRcvRef.get() != null, 3000);
            byte[] received = serverRcvRef.get();
            assert received != null : "server should receive data from Neta client";
            assert new String(received).equals("Hello from Neta client") : "data mismatch: " + new String(received);

            serverThread.join(3000);
        } finally {
            serverSocket.close();
            if (neta != null) {
                neta.shutdown();
            }
        }
    }

    // ── STREAM frame data extraction helper ────────────────────────────

    /** Extract data payload from a STREAM frame. */
    static byte[] extractStreamData(byte[] payload) {
        try {
            int pos = 0;
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];

            if (!QuicFrameType.isStream(frameType)) {
                return null;
            }

            boolean hasLen = QuicFrameType.streamLen(frameType);
            boolean hasOff = QuicFrameType.streamOff(frameType);

            // Skip stream ID
            long[] sidResult = QuicVarInt.decode(payload, pos);
            pos += (int) sidResult[1];

            // Skip offset if present
            if (hasOff) {
                long[] offResult = QuicVarInt.decode(payload, pos);
                pos += (int) offResult[1];
            }

            int dataLen;
            if (hasLen) {
                long[] lenResult = QuicVarInt.decode(payload, pos);
                dataLen = (int) lenResult[0];
                pos += (int) lenResult[1];
            } else {
                dataLen = payload.length - pos;
            }

            byte[] data = new byte[dataLen];
            System.arraycopy(payload, pos, data, 0, dataLen);
            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
