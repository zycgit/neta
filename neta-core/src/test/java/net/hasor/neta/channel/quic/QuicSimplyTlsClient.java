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
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.neta.codec.ssl.SslCertConfig;

/**
 * TLS 模式的轻量级 QUIC 客户端，专为协议合规测试设计。
 * <p>
 * 通过直接使用 neta 内部的 {@link QuicTlsEngine}、{@link QuicCrypto} 和 {@link QuicPacket}
 * 实现完整的 TLS 1.3 QUIC 握手，可在帧级别观察和验证握手过程。
 * <h3>握手流程（TLS）</h3>
 * <pre>
 *   Client → Server : Initial( CRYPTO{ClientHello} )           ← 加密 Initial 级别
 *   Server → Client : Initial( ACK + CRYPTO{ServerHello} )     ← 加密 Initial 级别
 *                   + Handshake( CRYPTO{EE+Cert+CV+Finished} ) ← 加密 Handshake 级别
 *   Client → Server : Handshake( CRYPTO{client Finished} )     ← 加密 Handshake 级别
 *   Server → Client : 1-RTT( HANDSHAKE_DONE )                  ← 加密 App 级别
 *   ——— 两端均进入 ESTABLISHED，连接就绪 ———
 * </pre>
 * <h3>包可见性</h3>
 * 本类位于 {@code net.hasor.neta.channel.quic} 包（与生产代码同包），
 * 因此可以访问包级私有的 {@link QuicTlsEngine}、{@link QuicCrypto} 和 {@link QuicPacket}。
 * 这是测试代码利用 Java 包级可见性的惯用手法。
 * @author Copilot / 赵永春 (zyc@hasor.net)
 */
public class QuicSimplyTlsClient implements Closeable {

    // ── 常量 ───────────────────────────────────────────────────────────
    public static final int QUIC_V1         = 0x00000001;
    public static final int TYPE_INITIAL    = 0x00;
    public static final int TYPE_HANDSHAKE  = 0x02;
    public static final int DEFAULT_CID_LEN = 8;

    // ── 握手状态 ───────────────────────────────────────────────────────
    // ── 实例字段 ───────────────────────────────────────────────────────
    private final byte[]               localCid;
    private final AtomicLong           initialPn          = new AtomicLong(0);
    private final AtomicLong           handshakePn        = new AtomicLong(0);
    private final AtomicLong           appPn              = new AtomicLong(0);
    private final DatagramSocket       socket;
    private final InetSocketAddress    serverAddr;
    // TLS engine and keys
    private final QuicTlsEngine        tlsEngine;
    // 握手事件追踪
    private final List<HandshakeEvent> handshakeEvents    = new ArrayList<HandshakeEvent>();
    private       byte[]               remoteCid;
    private       byte[][]             clientInitialKeys;
    private       byte[][]             serverInitialKeys;
    private       byte[][]             clientHandshakeKeys;
    private       byte[][]             serverHandshakeKeys;
    private       byte[][]             clientAppKeys;
    private       byte[][]             serverAppKeys;
    private       long                 largestInitialPn   = -1;
    private       long                 largestHandshakePn = -1;
    private       long                 largestAppPn       = -1;
    private       boolean              established        = false;

    /**
     * 创建一个 TLS 模式的 QUIC 客户端。
     * @param serverAddr 服务端地址
     * @param sslConfig TLS 证书配置（客户端侧，通常只需 trust 设置，不需私钥）
     * @throws Exception 套接字创建或 TLS 引擎初始化失败
     */
    public QuicSimplyTlsClient(InetSocketAddress serverAddr, SslCertConfig sslConfig) throws Exception {
        this.serverAddr = serverAddr;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5000);
        this.localCid = randomBytes(DEFAULT_CID_LEN);
        this.remoteCid = randomBytes(DEFAULT_CID_LEN);

        // 创建 QuicSoConfig 用于 TLS 引擎
        QuicSoConfig soConfig = new QuicSoConfig();
        soConfig.setSslConfig(sslConfig != null ? sslConfig : new SslCertConfig());
        this.tlsEngine = new QuicTlsEngine(sslConfig, soConfig, null, true);
    }

    // ── 构造 ───────────────────────────────────────────────────────────

    /** 检查 payload 中是否包含 HANDSHAKE_DONE 帧（类型 0x1e）。 */
    private static boolean containsHandshakeDone(byte[] payload) {
        if (payload == null)
            return false;
        int pos = 0;
        while (pos < payload.length) {
            int frameType = payload[pos] & 0xFF;
            if (frameType == 0x1e) {
                return true; // HANDSHAKE_DONE
            }
            if (frameType == 0x00) {
                pos++; // PADDING
                continue;
            }
            if (frameType == 0x01) {
                pos++; // PING
                continue;
            }
            // 其他帧：无法精确跳过，但 HANDSHAKE_DONE 通常在最前面
            break;
        }
        return false;
    }

    // ── TLS 握手 ───────────────────────────────────────────────────────

    /** 构建 STREAM 帧字节。 */
    private static byte[] buildStreamFrameBytes(long streamId, long offset, byte[] data, boolean fin) {
        byte type = (byte) 0x08;
        if (offset > 0)
            type |= 0x04;   // OFF bit
        type |= 0x02;                    // LEN bit
        if (fin)
            type |= 0x01;          // FIN bit

        byte[] siEnc = encodeVarInt(streamId);
        byte[] offEnc = (offset > 0) ? encodeVarInt(offset) : new byte[0];
        byte[] lenEnc = encodeVarInt(data.length);

        byte[] frame = new byte[1 + siEnc.length + offEnc.length + lenEnc.length + data.length];
        int pos = 0;
        frame[pos++] = type;
        System.arraycopy(siEnc, 0, frame, pos, siEnc.length);
        pos += siEnc.length;
        if (offEnc.length > 0) {
            System.arraycopy(offEnc, 0, frame, pos, offEnc.length);
            pos += offEnc.length;
        }
        System.arraycopy(lenEnc, 0, frame, pos, lenEnc.length);
        pos += lenEnc.length;
        System.arraycopy(data, 0, frame, pos, data.length);
        return frame;
    }

    // ── 1-RTT 帧操作 ──────────────────────────────────────────────────

    /** VarInt 编码（RFC 9000 §16）。 */
    static byte[] encodeVarInt(long value) {
        if (value < 0x40) {
            return new byte[] { (byte) value };
        } else if (value < 0x4000) {
            return new byte[] { (byte) (0x40 | (value >> 8)), (byte) value };
        } else if (value < 0x40000000L) {
            return new byte[] { (byte) (0x80 | (value >> 24)), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
        } else {
            return new byte[] { (byte) (0xC0 | (value >> 56)), (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32), (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * 扫描 TLS 握手消息流，返回到 Finished 消息（类型 0x14）末尾的总字节数。
     * 若尚未收到完整的 Finished 消息，返回 -1。
     */
    private static int calcHsExpectedLength(byte[] buf, int received) {
        int pos = 0;
        while (pos + 4 <= received) {
            int msgType = buf[pos] & 0xFF;
            int msgLen = ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            int msgEnd = pos + 4 + msgLen;
            if (msgType == 0x14) { // Finished
                return msgEnd;
            }
            if (msgEnd > received) {
                return -1; // 当前消息尚未完全接收
            }
            pos = msgEnd;
        }
        return -1; // 还没见到 Finished
    }

    // ── 分步握手（用于测试 0-RTT 注入场景）────────────────────────────────

    /**
     * 执行完整的 TLS QUIC 握手。
     * <p>
     * 完成后 {@link #isEstablished()} 返回 {@code true}，
     * 可通过 {@link #getHandshakeEvents()} 查看各阶段事件。
     * @throws Exception 握手失败
     */
    public void handshake() throws Exception {
        // ── Step 0: 从 client 的初始 DCID 派生 Initial 密钥 ────────────
        byte[][] initialSecrets = QuicCrypto.deriveInitialSecrets(remoteCid, QuicVersion.V1);
        this.clientInitialKeys = QuicCrypto.derivePacketKeys(initialSecrets[0], QuicVersion.V1);
        this.serverInitialKeys = QuicCrypto.derivePacketKeys(initialSecrets[1], QuicVersion.V1);

        // ── Step 1: 生成 ClientHello，发加密 Initial 包 ────────────────
        byte[] clientHello = tlsEngine.generateClientHello();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, clientHello);
        long pn = initialPn.getAndIncrement();
        byte[] packet = QuicPacket.buildLongHeaderPacket(TYPE_INITIAL, QUIC_V1, remoteCid, localCid, new byte[0], pn, cryptoFrame, clientInitialKeys[0], clientInitialKeys[1], clientInitialKeys[2], 1200);
        send(packet);
        handshakeEvents.add(HandshakeEvent.CLIENT_INITIAL_SENT);

        // ── Step 2: 接收 Server Initial (ServerHello) + Handshake 消息 ────────────
        // Server Handshake 消息可能跨多个 UDP 分片（QUIC Handshake 包分段）。
        // 使用重组缓冲区合并 CRYPTO 帧碎片，直到收到完整的 TLS Finished (type=0x14) 为止。
        byte[] resp = receiveRaw(5000);
        if (resp == null) {
            throw new IOException("TLS QUIC handshake timeout: no response to Initial");
        }

        byte[] hsFragBuf = null;      // CRYPTO 流重组缓冲区
        boolean[] hsFragBitmap = null; // 已接收字节位图（去重）
        int hsFragReceived = 0;        // 已接收唯一字节数
        boolean hsDataComplete = false;
        byte[] serverHandshakeData = null;

        // 最多读 10 次 UDP 数据报，直到 Handshake CRYPTO 流完整
        for (int readAttempt = 0; readAttempt < 10 && !hsDataComplete; readAttempt++) {
            if (readAttempt > 0) {
                resp = receiveRaw(2000);
                if (resp == null) {
                    break; // 超时，停止等待
                }
            }

            int offset = 0;
            while (offset < resp.length) {
                if (!QuicPacket.isLongHeader(new byte[] { resp[offset] })) {
                    break; // Short Header — unexpected at this stage
                }

                QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(resp, offset, resp.length - offset);
                if (parsed == null) {
                    break;
                }

                int packetType = QuicPacket.longHeaderType(resp[offset]);
                int totalLen = parsed.headerLength + parsed.payloadLength;

                if (packetType == TYPE_INITIAL) {
                    // 用 server 的 Initial 密钥解密
                    boolean ok = QuicPacket.decryptLongHeaderPacket(resp, offset, parsed, serverInitialKeys[0], serverInitialKeys[1], serverInitialKeys[2], largestInitialPn);
                    if (!ok) {
                        throw new IOException("Failed to decrypt Server Initial packet");
                    }
                    largestInitialPn = Math.max(largestInitialPn, parsed.packetNumber);

                    // 更新 remoteCid 为 server 的 SCID
                    if (parsed.scid != null && parsed.scid.length > 0) {
                        this.remoteCid = parsed.scid;
                    }

                    // 提取 ServerHello CRYPTO 帧
                    long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
                    if (cryptoInfo != null) {
                        int dataOffset = (int) cryptoInfo[1];
                        int dataLength = (int) cryptoInfo[2];
                        byte[] serverHello = new byte[dataLength];
                        System.arraycopy(parsed.payload, dataOffset, serverHello, 0, dataLength);

                        if (!tlsEngine.processServerHello(serverHello)) {
                            throw new IOException("Failed to process ServerHello");
                        }
                        // 获取 Handshake 密钥
                        this.clientHandshakeKeys = tlsEngine.getClientHandshakeKeys();
                        this.serverHandshakeKeys = tlsEngine.getServerHandshakeKeys();
                    }
                    handshakeEvents.add(HandshakeEvent.SERVER_INITIAL_RECEIVED);

                } else if (packetType == TYPE_HANDSHAKE) {
                    // Handshake 包 — 用 server 的 Handshake 密钥解密
                    if (serverHandshakeKeys == null) {
                        throw new IOException("Received Handshake packet before deriving keys");
                    }
                    boolean ok = QuicPacket.decryptLongHeaderPacket(resp, offset, parsed, serverHandshakeKeys[0], serverHandshakeKeys[1], serverHandshakeKeys[2], largestHandshakePn);
                    if (!ok) {
                        throw new IOException("Failed to decrypt Server Handshake packet");
                    }
                    largestHandshakePn = Math.max(largestHandshakePn, parsed.packetNumber);

                    // 提取所有 CRYPTO 帧并累加到重组缓冲区（支持分片）
                    int scanOffset = 0;
                    while (true) {
                        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, scanOffset);
                        if (cryptoInfo == null) {
                            break;
                        }
                        long fragStreamOff = cryptoInfo[0];
                        int dataOffset = (int) cryptoInfo[1];
                        int dataLength = (int) cryptoInfo[2];
                        scanOffset = dataOffset + dataLength;

                        int fragStart = (int) fragStreamOff;
                        int fragEnd = fragStart + dataLength;
                        if (hsFragBuf == null) {
                            hsFragBuf = new byte[Math.max(fragEnd, 4096)];
                            hsFragBitmap = new boolean[hsFragBuf.length];
                        }
                        if (fragEnd > hsFragBuf.length) {
                            hsFragBuf = Arrays.copyOf(hsFragBuf, fragEnd + 512);
                            hsFragBitmap = Arrays.copyOf(hsFragBitmap, hsFragBuf.length);
                        }
                        int newBytes = 0;
                        for (int i = 0; i < dataLength; i++) {
                            int bpos = fragStart + i;
                            if (!hsFragBitmap[bpos]) {
                                hsFragBitmap[bpos] = true;
                                newBytes++;
                            }
                        }
                        System.arraycopy(parsed.payload, dataOffset, hsFragBuf, fragStart, dataLength);
                        hsFragReceived += newBytes;
                    }
                    // Note: SERVER_HANDSHAKE_RECEIVED event is added only after full reassembly below
                }

                offset += totalLen;
            }

            // 检查重组是否完成：扫描 TLS 消息直到找到 Finished（type=0x14）
            if (hsFragBuf != null) {
                int expectedLen = calcHsExpectedLength(hsFragBuf, hsFragReceived);
                if (expectedLen > 0 && hsFragReceived >= expectedLen) {
                    serverHandshakeData = new byte[expectedLen];
                    System.arraycopy(hsFragBuf, 0, serverHandshakeData, 0, expectedLen);
                    hsDataComplete = true;
                    handshakeEvents.add(HandshakeEvent.SERVER_HANDSHAKE_RECEIVED);
                }
            }
        } // end for readAttempt

        if (serverHandshakeData == null) {
            throw new IOException("No complete server Handshake CRYPTO data received");
        }

        // 处理 EE + Certificate + CertificateVerify + Finished
        if (!tlsEngine.processServerHandshakeMessages(serverHandshakeData)) {
            throw new IOException("Failed to process server Handshake messages (TLS verification failed)");
        }

        // 获取 App 密钥
        this.clientAppKeys = tlsEngine.getClientAppKeys();
        this.serverAppKeys = tlsEngine.getServerAppKeys();

        // ── Step 4: 发送 client Finished（加密 Handshake 包）──────────
        byte[] clientFinished = tlsEngine.getClientFinishedBytes();
        byte[] finCrypto = QuicPacket.buildCryptoFrame(0, clientFinished);
        long hsPn = handshakePn.getAndIncrement();
        byte[] finPacket = QuicPacket.buildLongHeaderPacket(QuicVersion.V1, TYPE_HANDSHAKE, remoteCid, localCid, null, hsPn, finCrypto, clientHandshakeKeys[0], clientHandshakeKeys[1], clientHandshakeKeys[2], 0);
        send(finPacket);
        handshakeEvents.add(HandshakeEvent.CLIENT_HANDSHAKE_SENT);

        // ── Step 5: 接收 HANDSHAKE_DONE（加密 1-RTT 短头包）───────────
        resp = receiveRaw(5000);
        if (resp != null) {
            // 可能先收到 Handshake ACK，然后是 1-RTT HANDSHAKE_DONE
            // 尝试解析所有包
            int pktOffset = 0;
            while (pktOffset < resp.length) {
                if (QuicPacket.isLongHeader(new byte[] { resp[pktOffset] })) {
                    // Long Header — 可能是 Handshake ACK
                    QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(resp, pktOffset, resp.length - pktOffset);
                    if (parsed != null) {
                        int total = parsed.headerLength + parsed.payloadLength;
                        // 尝试解密 Handshake ACK
                        QuicPacket.decryptLongHeaderPacket(resp, pktOffset, parsed, serverHandshakeKeys[0], serverHandshakeKeys[1], serverHandshakeKeys[2], largestHandshakePn);
                        pktOffset += total;
                    } else {
                        break;
                    }
                } else {
                    // Short Header — 应该是 1-RTT HANDSHAKE_DONE
                    int dcidLen = localCid.length;
                    QuicPacket.ParsedPacket appParsed = QuicPacket.decryptShortHeaderPacket(resp, pktOffset, resp.length - pktOffset, dcidLen, serverAppKeys[0], serverAppKeys[1], serverAppKeys[2], largestAppPn);
                    if (appParsed != null) {
                        largestAppPn = Math.max(largestAppPn, appParsed.packetNumber);
                        // 检查是否包含 HANDSHAKE_DONE 帧（type = 0x1e）
                        if (containsHandshakeDone(appParsed.payload)) {
                            handshakeEvents.add(HandshakeEvent.HANDSHAKE_DONE_RECEIVED);
                            established = true;
                        }
                    }
                    break; // Short Header 后面不会再有其他包
                }
            }
        }

        // 如果第一次没收到 HANDSHAKE_DONE，再读一次
        if (!established) {
            resp = receiveRaw(5000);
            if (resp != null && resp.length > 0 && !QuicPacket.isLongHeader(new byte[] { resp[0] })) {
                int dcidLen = localCid.length;
                QuicPacket.ParsedPacket appParsed = QuicPacket.decryptShortHeaderPacket(resp, 0, resp.length, dcidLen, serverAppKeys[0], serverAppKeys[1], serverAppKeys[2], largestAppPn);
                if (appParsed != null) {
                    largestAppPn = Math.max(largestAppPn, appParsed.packetNumber);
                    if (containsHandshakeDone(appParsed.payload)) {
                        handshakeEvents.add(HandshakeEvent.HANDSHAKE_DONE_RECEIVED);
                        established = true;
                    }
                }
            }
        }

        if (!established) {
            throw new IOException("TLS QUIC handshake completed but HANDSHAKE_DONE not received");
        }
    }

    /**
     * 分步握手第一阶段：发送 ClientHello，接收并处理服务端 Initial + Handshake 响应。
     * <p>
     * 完成后 {@link #getRemoteCid()} 已更新为服务端 SCID，
     * 测试代码可将其作为 DCID 构造 TYPE_0RTT 包注入，
     * 注入完成后再调用 {@link #handshakePhase2()} 完成握手。
     * @throws Exception 任何握手步骤失败时抛出
     */
    public void handshakePhase1() throws Exception {
        // ── Step 0: 派生 Initial 密钥 ────────────────────────────────────
        byte[][] initialSecrets = QuicCrypto.deriveInitialSecrets(remoteCid, QuicVersion.V1);
        this.clientInitialKeys = QuicCrypto.derivePacketKeys(initialSecrets[0], QuicVersion.V1);
        this.serverInitialKeys = QuicCrypto.derivePacketKeys(initialSecrets[1], QuicVersion.V1);

        // ── Step 1: 发送 ClientHello（加密 Initial 包）────────────────────
        byte[] clientHello = tlsEngine.generateClientHello();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, clientHello);
        long pn = initialPn.getAndIncrement();
        byte[] packet = QuicPacket.buildLongHeaderPacket(TYPE_INITIAL, QUIC_V1, remoteCid, localCid, new byte[0], pn, cryptoFrame, clientInitialKeys[0], clientInitialKeys[1], clientInitialKeys[2], 1200);
        send(packet);
        handshakeEvents.add(HandshakeEvent.CLIENT_INITIAL_SENT);

        // ── Step 2+3: 接收服务端 Initial + Handshake，处理 ServerHello ─────
        byte[] resp = receiveRaw(5000);
        if (resp == null) {
            throw new IOException("TLS QUIC handshake timeout: no response to Initial");
        }

        int offset = 0;
        byte[] serverHandshakeData = null;

        // ── Handshake CRYPTO reassembly buffer (for fragmented Handshake) ──
        byte[] hsBuf = null;
        boolean[] hsBitmap = null;
        int hsReceived = 0;

        while (offset < resp.length) {
            if (!QuicPacket.isLongHeader(new byte[] { resp[offset] })) {
                break;
            }
            QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(resp, offset, resp.length - offset);
            if (parsed == null) {
                break;
            }
            int packetType = QuicPacket.longHeaderType(resp[offset]);
            int totalLen = parsed.headerLength + parsed.payloadLength;

            if (packetType == TYPE_INITIAL) {
                boolean ok = QuicPacket.decryptLongHeaderPacket(resp, offset, parsed, serverInitialKeys[0], serverInitialKeys[1], serverInitialKeys[2], largestInitialPn);
                if (!ok) {
                    throw new IOException("Failed to decrypt Server Initial packet");
                }
                largestInitialPn = Math.max(largestInitialPn, parsed.packetNumber);
                if (parsed.scid != null && parsed.scid.length > 0) {
                    this.remoteCid = parsed.scid;
                }
                long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
                if (cryptoInfo != null) {
                    int dataOffset = (int) cryptoInfo[1];
                    int dataLength = (int) cryptoInfo[2];
                    byte[] serverHello = new byte[dataLength];
                    System.arraycopy(parsed.payload, dataOffset, serverHello, 0, dataLength);
                    if (!tlsEngine.processServerHello(serverHello)) {
                        throw new IOException("Failed to process ServerHello");
                    }
                    this.clientHandshakeKeys = tlsEngine.getClientHandshakeKeys();
                    this.serverHandshakeKeys = tlsEngine.getServerHandshakeKeys();
                }
                handshakeEvents.add(HandshakeEvent.SERVER_INITIAL_RECEIVED);
            } else if (packetType == TYPE_HANDSHAKE) {
                if (serverHandshakeKeys == null) {
                    throw new IOException("Received Handshake packet before deriving keys");
                }
                boolean ok = QuicPacket.decryptLongHeaderPacket(resp, offset, parsed, serverHandshakeKeys[0], serverHandshakeKeys[1], serverHandshakeKeys[2], largestHandshakePn);
                if (!ok) {
                    throw new IOException("Failed to decrypt Server Handshake packet");
                }
                largestHandshakePn = Math.max(largestHandshakePn, parsed.packetNumber);
                // Accumulate CRYPTO fragments into reassembly buffer
                int scanOff = 0;
                while (scanOff < parsed.payload.length) {
                    long[] ci = QuicPacket.parseCryptoFrame(parsed.payload, scanOff);
                    if (ci == null)
                        break;
                    int fragStreamOff = (int) ci[0];
                    int dataOff = (int) ci[1];
                    int dataLen = (int) ci[2];
                    scanOff = dataOff + dataLen;
                    int fragEnd = fragStreamOff + dataLen;
                    if (hsBuf == null) {
                        hsBuf = new byte[Math.max(fragEnd, 4096)];
                        hsBitmap = new boolean[hsBuf.length];
                    }
                    if (fragEnd > hsBuf.length) {
                        hsBuf = java.util.Arrays.copyOf(hsBuf, fragEnd + 512);
                        hsBitmap = java.util.Arrays.copyOf(hsBitmap, hsBuf.length);
                    }
                    int newBytes = 0;
                    for (int i = 0; i < dataLen; i++) {
                        if (!hsBitmap[fragStreamOff + i]) {
                            hsBitmap[fragStreamOff + i] = true;
                            newBytes++;
                        }
                    }
                    System.arraycopy(parsed.payload, dataOff, hsBuf, fragStreamOff, dataLen);
                    hsReceived += newBytes;
                }
            }
            offset += totalLen;
        }

        // ── Step 3b: Read additional datagrams for remaining Handshake fragments ──
        for (int attempt = 0; attempt < 5 && serverHandshakeData == null; attempt++) {
            // Check if reassembly is complete
            if (hsBuf != null) {
                int expectedLen = calcHsExpectedLength(hsBuf, hsReceived);
                if (expectedLen > 0 && hsReceived >= expectedLen) {
                    serverHandshakeData = new byte[expectedLen];
                    System.arraycopy(hsBuf, 0, serverHandshakeData, 0, expectedLen);
                    handshakeEvents.add(HandshakeEvent.SERVER_HANDSHAKE_RECEIVED);
                    break;
                }
            }
            resp = receiveRaw(5000);
            if (resp == null) {
                throw new IOException("TLS QUIC handshake timeout: no Handshake packet from server");
            }
            // Process all packets in this datagram
            int off2 = 0;
            while (off2 < resp.length) {
                if (!QuicPacket.isLongHeader(new byte[] { resp[off2] }))
                    break;
                QuicPacket.ParsedPacket p2 = QuicPacket.parseLongHeader(resp, off2, resp.length - off2);
                if (p2 == null)
                    break;
                int total2 = p2.headerLength + p2.payloadLength;
                int pt2 = QuicPacket.longHeaderType(resp[off2]);
                if (pt2 == TYPE_HANDSHAKE && serverHandshakeKeys != null) {
                    boolean ok = QuicPacket.decryptLongHeaderPacket(resp, off2, p2, serverHandshakeKeys[0], serverHandshakeKeys[1], serverHandshakeKeys[2], largestHandshakePn);
                    if (ok) {
                        largestHandshakePn = Math.max(largestHandshakePn, p2.packetNumber);
                        int scanOff = 0;
                        while (scanOff < p2.payload.length) {
                            long[] ci = QuicPacket.parseCryptoFrame(p2.payload, scanOff);
                            if (ci == null)
                                break;
                            int fragStreamOff = (int) ci[0];
                            int dataOff = (int) ci[1];
                            int dataLen = (int) ci[2];
                            scanOff = dataOff + dataLen;
                            int fragEnd = fragStreamOff + dataLen;
                            if (hsBuf == null) {
                                hsBuf = new byte[Math.max(fragEnd, 4096)];
                                hsBitmap = new boolean[hsBuf.length];
                            }
                            if (fragEnd > hsBuf.length) {
                                hsBuf = java.util.Arrays.copyOf(hsBuf, fragEnd + 512);
                                hsBitmap = java.util.Arrays.copyOf(hsBitmap, hsBuf.length);
                            }
                            int newBytes = 0;
                            for (int i = 0; i < dataLen; i++) {
                                if (!hsBitmap[fragStreamOff + i]) {
                                    hsBitmap[fragStreamOff + i] = true;
                                    newBytes++;
                                }
                            }
                            System.arraycopy(p2.payload, dataOff, hsBuf, fragStreamOff, dataLen);
                            hsReceived += newBytes;
                        }
                    }
                }
                off2 += total2;
            }
        }

        if (serverHandshakeData == null) {
            throw new IOException("No server Handshake CRYPTO data received");
        }
        if (!tlsEngine.processServerHandshakeMessages(serverHandshakeData)) {
            throw new IOException("Failed to process server Handshake messages (TLS verification failed)");
        }
        // 派生 App 密钥（第二阶段发送 Finished 时使用）
        this.clientAppKeys = tlsEngine.getClientAppKeys();
        this.serverAppKeys = tlsEngine.getServerAppKeys();
    }

    // ── Getters ────────────────────────────────────────────────────────

    /**
     * 分步握手第二阶段：发送 client Finished，等待 HANDSHAKE_DONE。
     * <p>
     * 须在 {@link #handshakePhase1()} 之后调用。成功后 {@link #isEstablished()} 返回 {@code true}。
     * @throws Exception 发送失败或未收到 HANDSHAKE_DONE 时抛出
     */
    public void handshakePhase2() throws Exception {
        // ── Step 4: 发送 client Finished ─────────────────────────────────
        byte[] clientFinished = tlsEngine.getClientFinishedBytes();
        byte[] finCrypto = QuicPacket.buildCryptoFrame(0, clientFinished);
        long hsPn = handshakePn.getAndIncrement();
        byte[] finPacket = QuicPacket.buildLongHeaderPacket(QuicVersion.V1, TYPE_HANDSHAKE, remoteCid, localCid, null, hsPn, finCrypto, clientHandshakeKeys[0], clientHandshakeKeys[1], clientHandshakeKeys[2], 0);
        send(finPacket);
        handshakeEvents.add(HandshakeEvent.CLIENT_HANDSHAKE_SENT);

        // ── Step 5: 接收 HANDSHAKE_DONE ──────────────────────────────────
        byte[] resp = receiveRaw(5000);
        if (resp != null) {
            int offset = 0;
            while (offset < resp.length) {
                if (QuicPacket.isLongHeader(new byte[] { resp[offset] })) {
                    QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(resp, offset, resp.length - offset);
                    if (parsed != null) {
                        int total = parsed.headerLength + parsed.payloadLength;
                        QuicPacket.decryptLongHeaderPacket(resp, offset, parsed, serverHandshakeKeys[0], serverHandshakeKeys[1], serverHandshakeKeys[2], largestHandshakePn);
                        offset += total;
                    } else {
                        break;
                    }
                } else {
                    int dcidLen = localCid.length;
                    QuicPacket.ParsedPacket appParsed = QuicPacket.decryptShortHeaderPacket(resp, offset, resp.length - offset, dcidLen, serverAppKeys[0], serverAppKeys[1], serverAppKeys[2], largestAppPn);
                    if (appParsed != null) {
                        largestAppPn = Math.max(largestAppPn, appParsed.packetNumber);
                        if (containsHandshakeDone(appParsed.payload)) {
                            handshakeEvents.add(HandshakeEvent.HANDSHAKE_DONE_RECEIVED);
                            established = true;
                        }
                    }
                    break;
                }
            }
        }
        if (!established) {
            resp = receiveRaw(5000);
            if (resp != null && resp.length > 0 && !QuicPacket.isLongHeader(new byte[] { resp[0] })) {
                int dcidLen = localCid.length;
                QuicPacket.ParsedPacket appParsed = QuicPacket.decryptShortHeaderPacket(resp, 0, resp.length, dcidLen, serverAppKeys[0], serverAppKeys[1], serverAppKeys[2], largestAppPn);
                if (appParsed != null) {
                    largestAppPn = Math.max(largestAppPn, appParsed.packetNumber);
                    if (containsHandshakeDone(appParsed.payload)) {
                        handshakeEvents.add(HandshakeEvent.HANDSHAKE_DONE_RECEIVED);
                        established = true;
                    }
                }
            }
        }
        if (!established) {
            throw new IOException("TLS QUIC handshake phase 2 completed but HANDSHAKE_DONE not received");
        }
    }

    /**
     * 发送加密的 PING 帧（1-RTT）。
     */
    public void sendPing() throws Exception {
        byte[] pingFrame = new byte[] { 0x01 }; // PING = 0x01
        long pn = appPn.getAndIncrement();
        byte[] packet = QuicPacket.buildShortHeaderPacket(remoteCid, pn, pingFrame, clientAppKeys[0], clientAppKeys[1], clientAppKeys[2]);
        send(packet);
    }

    /**
     * 发送加密的 STREAM 帧（1-RTT）。
     */
    public void sendStreamFrame(long streamId, long offset, byte[] data, boolean fin) throws Exception {
        byte[] streamFrame = buildStreamFrameBytes(streamId, offset, data, fin);
        long pn = appPn.getAndIncrement();
        byte[] packet = QuicPacket.buildShortHeaderPacket(remoteCid, pn, streamFrame, clientAppKeys[0], clientAppKeys[1], clientAppKeys[2]);
        send(packet);
    }

    /**
     * 接收并解密 1-RTT 包的 payload。
     * @param timeoutMs 超时毫秒
     * @return 解密后的 payload，超时返回 {@code null}
     */
    public byte[] receive1RttPayload(int timeoutMs) throws Exception {
        byte[] raw = receiveRaw(timeoutMs);
        if (raw == null)
            return null;

        // 跳过可能前置的 Long Header 包
        int offset = 0;
        while (offset < raw.length && QuicPacket.isLongHeader(new byte[] { raw[offset] })) {
            QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(raw, offset, raw.length - offset);
            if (parsed == null)
                break;
            offset += parsed.headerLength + parsed.payloadLength;
        }

        if (offset >= raw.length)
            return null;

        // 解密 Short Header 包
        int dcidLen = localCid.length;
        QuicPacket.ParsedPacket appParsed = QuicPacket.decryptShortHeaderPacket(raw, offset, raw.length - offset, dcidLen, serverAppKeys[0], serverAppKeys[1], serverAppKeys[2], largestAppPn);
        if (appParsed != null) {
            largestAppPn = Math.max(largestAppPn, appParsed.packetNumber);
            return appParsed.payload;
        }
        return null;
    }

    public byte[] getLocalCid() {
        return localCid;
    }

    public byte[] getRemoteCid() {
        return remoteCid;
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    public DatagramSocket getSocket() {
        return socket;
    }

    public boolean isEstablished() {
        return established;
    }

    /**
     * 返回完整的握手事件列表（按时间顺序）。
     */
    public List<HandshakeEvent> getHandshakeEvents() {
        return handshakeEvents;
    }

    /**
     * 返回服务端的证书链（TLS 握手期间从 Certificate 消息提取）。
     */
    public X509Certificate[] getServerCertChain() {
        return tlsEngine.getPeerCertChain();
    }

    /**
     * 接收原始 UDP 数据报。
     * @param timeoutMs 超时毫秒
     * @return 接收到的数据，超时返回 {@code null}
     */

    private void send(byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, serverAddr));
    }

    public byte[] receiveRaw(int timeoutMs) throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        int oldTimeout = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMs);
        try {
            socket.receive(dp);
            byte[] result = new byte[dp.getLength()];
            System.arraycopy(buf, 0, result, 0, dp.getLength());
            return result;
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(oldTimeout);
        }
    }

    @Override
    public void close() throws IOException {
        if (!socket.isClosed()) {
            socket.close();
        }
    }

    /** 握手过程中捕获的各阶段事件。 */
    public enum HandshakeEvent {
        /** 客户端发送 Initial (ClientHello) */
        CLIENT_INITIAL_SENT,
        /** 收到服务端 Initial (ServerHello) */
        SERVER_INITIAL_RECEIVED,
        /** 收到服务端 Handshake (EncryptedExtensions + Certificate + CertVerify + Finished) */
        SERVER_HANDSHAKE_RECEIVED,
        /** 客户端发送 Handshake (client Finished) */
        CLIENT_HANDSHAKE_SENT,
        /** 收到 HANDSHAKE_DONE 帧 */
        HANDSHAKE_DONE_RECEIVED
    }
}
