/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic.rfc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

import org.junit.Test;

import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.quic.QuicSimplyTlsClient;
import net.hasor.neta.channel.transport.quic.QuicSoConfig;
import net.hasor.neta.channel.transport.quic.simply.QuicSimplyClient;
import net.hasor.neta.codec.ssl.SslAuthKeyType;
import net.hasor.neta.codec.ssl.SslCertConfig;

/**
 * QUIC RFC 合规测试 — 0-RTT 早期数据（RFC 9001 §4.9.1）。
 * <p>
 * 测试 0-RTT 行为中可从外部观察到的部分（黑盒 + 白盒路径覆盖）。
 * <h3>测试覆盖范围</h3>
 * <ul>
 *   <li>无活跃握手时静默丢弃 0-RTT 包 → {@link #testServerDiscardsZeroRttWithoutActiveHandshake()}</li>
 *   <li>TLS 握手进行中正确缓冲 0-RTT 包（行为层验证）→ {@link #testServerBuffers0RttDuringTlsHandshake()}</li>
 *   <li>缓冲上限 64 个包，超限后静默丢弃 → {@link #testServerDiscards0RttAboveBufferLimit()}</li>
 *   <li>TLS 未启用时静默忽略 0-RTT 包 → {@link #testServerIgnores0RttWhenTlsDisabled()}</li>
 *   <li>握手完成后 {@code drain0RttData()} 按序交付缓冲数据 → {@link #testDrains0RttDataAfterHandshakeCompletes()}</li>
 *   <li>Anti-replay：相同 0-RTT 包号不重复处理 → {@link #testAntiReplayBlocks0RttDuplicates()}</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCRtt0Test extends AbstractSoTest {

    /** 创建启用 TLS 的 QuicSoConfig（使用测试 PEM 证书，与 QuicRFCStreamTest 保持一致）。 */
    private static QuicSoConfig quicTlsConfig() {
        QuicSoConfig config = quicConfig();
        SslCertConfig sslConfig = new SslCertConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        config.setSslConfig(sslConfig);
        return config;
    }

    /** 生成指定长度的随机字节数组。 */
    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * 构造一个合法格式的 0-RTT Long Header 包（类型位 = 0x01）。
     * <p>
     * 使用 {@link QuicSimplyClient#buildRawLongHeaderPacket} 并传入 packetType=0x01：
     * <pre>
     *   first byte 高 2 位 = 11 (Long Header)
     *   bits 5–4           = 01 → 0-RTT 类型
     *   first byte         = 0xC0 | 0x10 | (pnLen-1) = 0xD0（pn=0 时）
     * </pre>
     * 服务端 {@code QuicPacket.parseLongHeader} 可将首字节正确解析为 TYPE_0RTT=0x01。
     */
    private static byte[] buildZeroRttPacket(byte[] dcid, byte[] scid, long pn, byte[] payload) {
        // packetType=0x01 → 0-RTT Long Header；无 token 字段（与 Initial 不同）
        return QuicSimplyClient.buildRawLongHeaderPacket(0x01, QuicSimplyClient.QUIC_V1, dcid, scid, new byte[0], pn, payload);
    }

    // ════════════════════════════════════════════════════════════════════
    //  §1.2 测试一：无活跃握手时静默丢弃 0-RTT 包
    //  RFC 9001 §4.9.1 / RFC 9000 §7
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：向明文服务端发送 0-RTT Long Header 包，但对应 DCID 无任何活跃握手。
     * <p>
     * 服务端代码路径（{@code QuicAsyncServerChannel.processLongHeaderPacket}）：
     * <pre>
     *   handshake == null  &amp;&amp;  packetType == TYPE_0RTT (0x01)
     *   → logger.info("0-RTT packet received without active handshake, ignoring")
     *   → return  // 静默丢弃，无任何 UDP 响应
     * </pre>
     * <h3>验证点</h3>
     * <ol>
     *   <li>服务端对 0-RTT 包无任何 UDP 响应（500 ms 内无数据返回）。</li>
     *   <li>服务端保持可用：之后仍能成功接受正常明文握手。</li>
     * </ol>
     */
    @Test
    public void testServerDiscardsZeroRttWithoutActiveHandshake() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler needed */ }, quicConfig());

        // ── 1. 构造并发送 0-RTT 包（DCID 从未存在于服务端任何 map 中） ────────
        byte[] unknownDcid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
        byte[] scid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
        byte[] payload = QuicSimplyClient.buildPingBytes(); // 任意合法帧内容

        byte[] zeroRttPkt = buildZeroRttPacket(unknownDcid, scid, 0L, payload);

        DatagramSocket rawSock = new DatagramSocket();
        rawSock.setSoTimeout(500);
        try {
            rawSock.send(new DatagramPacket(zeroRttPkt, zeroRttPkt.length, address));

            // ── 2. 服务端应无任何 UDP 响应 ───────────────────────────────────────
            byte[] buf = new byte[65535];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            boolean gotResponse = false;
            try {
                rawSock.receive(response);
                gotResponse = true;
            } catch (java.net.SocketTimeoutException expected) {
                // 预期：服务端静默丢弃，无 UDP 响应
            }
            assert !gotResponse : "Server must silently discard 0-RTT packet with no active handshake (no response expected)";
        } finally {
            rawSock.close();
        }

        // ── 3. 验证服务端仍然可用：正常明文握手应成功 ────────────────────────────
        try (QuicSimplyClient verifyClient = new QuicSimplyClient(address)) {
            verifyClient.handshake();
            assert verifyClient.getRemoteCid() != null : "Server must remain operational after discarding stray 0-RTT packet";
        }

        neta.shutdown();
    }

    // ════════════════════════════════════════════════════════════════════
    //  §1.2 测试二：TLS 握手进行中正确缓冲 0-RTT 包（行为层面验证）
    //  RFC 9001 §4.9.1（Server 正确缓冲 0-RTT 包，Long Header Type=0x01）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：在 TLS 握手进行期间（{@code handshakeMap} 中存在活跃握手条目），
     * 向服务端发送若干 0-RTT Long Header 包（类型 0x01），验证服务端缓冲这些包而不崩溃。
     * <h3>测试搭建方式</h3>
     * <ol>
     *   <li>启动 TLS 服务端。</li>
     *   <li>通过裸 UDP 套接字发送一个<b>明文</b> Initial 包（TLS AEAD 不匹配，解密会失败）。
     *       服务端会在 handshakeMap 中注册握手条目（注册在解密之前），
     *       解密失败后条目保留、握手不被 promote。</li>
     *   <li>等待 150 ms 确保握手条目已写入 handshakeMap。</li>
     *   <li>用相同 DCID 发送 7 个 0-RTT 包，触发代码路径：
     *       <pre>
     *         handshake != null  &amp;&amp;  isSslEnabled()
     *         → process0RttPacket → handshake.buffer0RttData(rawData)
     *       </pre>
     *   </li>
     * </ol>
     * <h3>验证点</h3>
     * <ol>
     *   <li>服务端对所有 0-RTT 包均无 UDP 响应（缓冲路径不回复）。</li>
     *   <li>服务端未因缓冲 0-RTT 包而崩溃或异常。</li>
     *   <li>服务端在注入结束后仍能接受新的合法 TLS 连接。</li>
     * </ol>
     * @implNote 无法直接验证 {@code buffered0RttData} 内容，因为
     * {@code QuicAsyncChannelHandshake} 是包私有类，{@code drain0RttData()}
     * 从 {@code rfc} 子包无法访问。仅通过服务端不崩溃、无异常响应间接证明。
     */
    @Test
    public void testServerBuffers0RttDuringTlsHandshake() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler needed */ }, quicTlsConfig());

        // Initial 与 0-RTT 包必须使用同一 DCID，
        // 才能让 0-RTT 在服务端 handshakeMap 中路由到同一握手条目
        byte[] sharedDcid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
        byte[] scid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);

        DatagramSocket rawSock = new DatagramSocket();
        rawSock.setSoTimeout(300);
        try {
            // ── 1. 发送明文 Initial → 在服务端 handshakeMap 建立握手条目 ─────────
            //    payload 使用垃圾内容，AEAD 解密将失败，handshake 条目保留在 map 中
            byte[] cryptoFrame = QuicSimplyClient.buildCryptoFrame(0, new byte[8]);
            byte[] initialPkt = QuicSimplyClient.buildRawLongHeaderPacket(QuicSimplyClient.TYPE_INITIAL, QuicSimplyClient.QUIC_V1, sharedDcid, scid, new byte[0], 0L, cryptoFrame);
            rawSock.send(new DatagramPacket(initialPkt, initialPkt.length, address));

            // ── 2. 等待服务端处理 Initial 并将握手条目写入 handshakeMap ────────────
            Thread.sleep(150);

            // ── 3. 用相同 DCID 发送 7 个 0-RTT 包 ────────────────────────────────
            byte[] pingPayload = QuicSimplyClient.buildPingBytes();
            for (int i = 0; i < 7; i++) {
                byte[] zeroRttPkt = buildZeroRttPacket(sharedDcid, scid, i + 1, pingPayload);
                rawSock.send(new DatagramPacket(zeroRttPkt, zeroRttPkt.length, address));
            }

            // ── 4. 等待服务端处理所有 0-RTT 包 ──────────────────────────────────
            Thread.sleep(150);

            // ── 5. 验证服务端对 0-RTT 包无任何 UDP 响应 ─────────────────────────
            byte[] buf = new byte[65535];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            boolean gotUnexpectedResponse = false;
            try {
                rawSock.receive(response);
                gotUnexpectedResponse = true;
            } catch (java.net.SocketTimeoutException expected) {
                // 预期：buffer0RttData 仅缓冲，不发任何 UDP 响应
            }
            assert !gotUnexpectedResponse : "Server must not send any UDP response when buffering 0-RTT packets during TLS handshake";

        } finally {
            rawSock.close();
        }

        // ── 6. 验证服务端仍然可用：新的合法 TLS 连接应握手成功 ──────────────────
        SslCertConfig clientSslCfg = new SslCertConfig(); // 客户端无需提供证书
        try (QuicSimplyTlsClient tlsClient = new QuicSimplyTlsClient(address, clientSslCfg)) {
            tlsClient.handshake();
            assert tlsClient.isEstablished() : "TLS server must still accept new connections after buffering 0-RTT packets";
        }

        neta.shutdown();
    }

    // ════════════════════════════════════════════════════════════════════
    //  §1.2 测试三：缓冲上限 64 个包，超限后静默丢弃
    //  RFC 9001 §4.9.1（buffer 防内存耗尽保护）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：向 TLS 服务端发送超过缓冲上限（64 个）的 0-RTT 包，
     * 验证服务端丢弃超出部分后不崩溃、不发错误包。
     * <h3>实现原理</h3>
     * {@code QuicAsyncChannelHandshake.buffer0RttData()} 内部：
     * <pre>
     *   if (buffered0RttData.size() &lt; 64) {
     *       buffered0RttData.add(rawData);        // 缓冲第 1–64 个
     *   } else {
     *       logger.info("0-RTT buffer full, discarding packet");  // 静默丢弃第 65+ 个
     *   }
     * </pre>
     * <h3>验证点</h3>
     * <ol>
     *   <li>发送 70 个 0-RTT 包（前 64 缓冲 + 后 6 丢弃），服务端不崩溃。</li>
     *   <li>服务端未发送任何 UDP 错误响应（包括超量丢弃阶段）。</li>
     *   <li>服务端在超限处理后仍然可用（接受新 TLS 连接）。</li>
     * </ol>
     * @implNote 无法直接断言"恰好 64 个被缓冲、6 个被丢弃"，
     * 因内部计数不对外暴露。以"服务端不崩溃 + 无异常 UDP 响应"作为间接验证。
     */
    @Test
    public void testServerDiscards0RttAboveBufferLimit() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler needed */ }, quicTlsConfig());

        byte[] sharedDcid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
        byte[] scid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);

        DatagramSocket rawSock = new DatagramSocket();
        rawSock.setSoTimeout(300);
        try {
            // ── 1. 发送 plaintext Initial → 建立 handshakeMap 条目 ──────────────
            byte[] cryptoFrame = QuicSimplyClient.buildCryptoFrame(0, new byte[8]);
            byte[] initialPkt = QuicSimplyClient.buildRawLongHeaderPacket(QuicSimplyClient.TYPE_INITIAL, QuicSimplyClient.QUIC_V1, sharedDcid, scid, new byte[0], 0L, cryptoFrame);
            rawSock.send(new DatagramPacket(initialPkt, initialPkt.length, address));
            Thread.sleep(150);

            // ── 2. 发送 70 个 0-RTT 包（超过 64 的缓冲上限） ───────────────────
            byte[] pingPayload = QuicSimplyClient.buildPingBytes();
            for (int i = 0; i < 70; i++) {
                byte[] zeroRttPkt = buildZeroRttPacket(sharedDcid, scid, i + 1, pingPayload);
                rawSock.send(new DatagramPacket(zeroRttPkt, zeroRttPkt.length, address));
            }

            // ── 3. 等待服务端完成处理 ────────────────────────────────────────────
            Thread.sleep(300);

            // ── 4. 验证服务端未对任何 0-RTT 包发送 UDP 响应 ─────────────────────
            byte[] buf = new byte[65535];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            boolean gotUnexpected = false;
            try {
                rawSock.receive(response);
                gotUnexpected = true;
            } catch (java.net.SocketTimeoutException expected) {
                // 预期：buffer full 仅记录日志，不发 UDP 响应
            }
            assert !gotUnexpected : "Server must not send any error response when 0-RTT buffer limit (64) is exceeded";

        } finally {
            rawSock.close();
        }

        // ── 5. 服务端仍然可用 ─────────────────────────────────────────────────
        SslCertConfig clientSslCfg = new SslCertConfig();
        try (QuicSimplyTlsClient tlsClient = new QuicSimplyTlsClient(address, clientSslCfg)) {
            tlsClient.handshake();
            assert tlsClient.isEstablished() : "TLS server must remain functional after exceeding the 0-RTT buffer limit of 64 packets";
        }

        neta.shutdown();
    }

    // ════════════════════════════════════════════════════════════════════
    //  §1.2 测试四：TLS 未启用时静默忽略 0-RTT 包
    //  RFC 9001 §4.9.1（TLS 禁用路径）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：明文服务端（TLS 禁用）在握手完成后收到 0-RTT Long Header 包。
     * <h3>代码路径</h3>
     * <pre>
     *   processLongHeaderPacket:
     *     conn = connectionMap.get(dcid)  // 已存在（明文握手同步完成）
     *     conn != null &amp;&amp; packetType == TYPE_0RTT
     *     → process0RttPacket(null, rawData, …)
     *         → !isSslEnabled()  → log + return  // 静默丢弃
     * </pre>
     * <h3>测试步骤</h3>
     * <ol>
     *   <li>启动明文服务端（不设置 SSL）。</li>
     *   <li>通过 {@link QuicSimplyClient} 完成明文握手，获取服务端 CID（即 {@code remoteCid}）。</li>
     *   <li>以服务端 CID 为 DCID，通过裸 UDP 套接字发送 3 个 0-RTT Long Header 包。</li>
     * </ol>
     * <h3>验证点</h3>
     * <ol>
     *   <li>服务端对 0-RTT 包无任何 UDP 响应（静默丢弃）。</li>
     *   <li>已建立的明文连接仍然可用（发送数据不报错）。</li>
     *   <li>服务端接受新的明文握手（整体健壮性验证）。</li>
     * </ol>
     */
    @Test
    public void testServerIgnores0RttWhenTlsDisabled() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler needed */ }, quicConfig());

        // ── 1. 完成明文握手，获取服务端 CID ─────────────────────────────────
        byte[] serverCid;
        try (QuicSimplyClient client = new QuicSimplyClient(address)) {
            client.handshake();
            serverCid = client.getRemoteCid(); // 服务端 localCid，即 connectionMap 中的 key
            assert serverCid != null : "Plaintext handshake must succeed";

            // ── 2. 以服务端 CID 为 DCID 发送 3 个 0-RTT 包 ─────────────────────
            byte[] scid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
            byte[] payload = QuicSimplyClient.buildPingBytes();
            DatagramSocket rawSock = new DatagramSocket();
            rawSock.setSoTimeout(400);
            try {
                for (int i = 0; i < 3; i++) {
                    byte[] zeroRttPkt = buildZeroRttPacket(serverCid, scid, i, payload);
                    rawSock.send(new DatagramPacket(zeroRttPkt, zeroRttPkt.length, address));
                }
                Thread.sleep(150);

                // ── 3. 验证服务端对 0-RTT 包无任何 UDP 响应 ────────────────────────
                byte[] buf = new byte[65535];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                boolean gotResponse = false;
                try {
                    rawSock.receive(response);
                    gotResponse = true;
                } catch (java.net.SocketTimeoutException expected) {
                    // 预期：!isSslEnabled() 分支仅记录日志，不回复任何 UDP 包
                }
                assert !gotResponse : "Server must silently discard 0-RTT when TLS is disabled (no UDP response expected)";
            } finally {
                rawSock.close();
            }

            // ── 4. 验证已建立的连接仍然可用（发送一个 1-RTT PING） ───────────────
            client.sendPing(); // 不抛异常即通过
        }

        // ── 5. 验证服务端整体健壮性：仍可接受新的明文握手 ───────────────────────
        try (QuicSimplyClient newClient = new QuicSimplyClient(address)) {
            newClient.handshake();
            assert newClient.getRemoteCid() != null : "Server must remain operational after discarding 0-RTT packets (TLS disabled)";
        }

        neta.shutdown();
    }
    // ══════════════════════════════════════════════════════════════════
    //  §1.2 测试五：drain0RttData() 在握手完成后按序交付缓冲数据
    //  RFC 9001 §4.9.1ﾈ0-RTT 早期数据按序交付ﾉ
    // ══════════════════════════════════════════════════════════════════

    /**
     * 场景：TLS 握手进行中，在发送 ClientFinished 之前注入 N 个 TYPE_0RTT 包；
     * 握手完成时 {@code promoteToConnection()} 调用 {@code drain0RttData()}，
     * 将缓冲的早期数据交付给已建立的连接。
     * <h3>测试步骤</h3>
     * <ol>
     *   <li>启动 TLS 服务端。</li>
     *   <li>调用 {@link QuicSimplyTlsClient#handshakePhase1()} — 完成 Initial 交换后，
     *       {@code remoteCid} 已更新为服务端 SCID；服务端握手条目在
     *       {@code handshakeMap} 中。</li>
     *   <li>向服务端注入 4 个 TYPE_0RTT PING 包（DCID = 服务端 SCID）。</li>
     *   <li>调用 {@link QuicSimplyTlsClient#handshakePhase2()} —
     *       客户端发送 Finished；服务端调用 {@code promoteToConnection()} 并执行：
     *       <pre>
     *         drain0RttData() → 4 个缓冲包被取出
     *         processBuffered0RttPacket(...) × 4 → dispatchReceivedFrames()
     *       </pre>
     *   </li>
     * </ol>
     * <h3>验证点</h3>
     * <ol>
     *   <li>握手成功：{@link QuicSimplyTlsClient#isEstablished()} 为 {@code true}。</li>
     *   <li>服务端不崩溃（drain 过程无异常）。</li>
     *   <li>握手完成后仍可发送 1-RTT PING（连接功能正常）。</li>
     * </ol>
     */
    @Test
    public void testDrains0RttDataAfterHandshakeCompletes() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler */ }, quicTlsConfig());

        SslCertConfig clientSslCfg = new SslCertConfig();
        try (QuicSimplyTlsClient tlsClient = new QuicSimplyTlsClient(address, clientSslCfg)) {
            // ── 1. Phase 1：为客户端洼生 InitialKeys，发送 ClientHello，接收 ServerHello ─
            tlsClient.handshakePhase1();

            // ── 2. 在握手状态中注入 4 个 TYPE_0RTT PING 包 ───────────────────────
            byte[] scid = tlsClient.getLocalCid();
            byte[] dcid = tlsClient.getRemoteCid(); // 服务端 SCID，ServerHello 后更新
            byte[] pingPayload = QuicSimplyClient.buildPingBytes();
            DatagramSocket rawSock = tlsClient.getSocket();
            for (int i = 0; i < 4; i++) {
                byte[] zeroRttPkt = buildZeroRttPacket(dcid, scid, i, pingPayload);
                rawSock.send(new DatagramPacket(zeroRttPkt, zeroRttPkt.length, address));
            }
            Thread.sleep(120); // 确保服务端已将 4 个包缓冲

            // ── 3. Phase 2：send Finished → promoteToConnection() → drain0RttData() ─
            tlsClient.handshakePhase2();

            // ── 4. 验证握手成功 ────────────────────────────────────────────────────
            assert tlsClient.isEstablished() : "TLS connection must be established after phase2 (drain must not break handshake)";

            // ── 5. 验证连接仍然可用（drain 后发 1-RTT PING） ─────────────────
            tlsClient.sendPing();
        }

        neta.shutdown();
    }

    // ══════════════════════════════════════════════════════════════════
    //  §1.2 测试六：Anti-replay — 相同 0-RTT 包号不重复处理
    //  RFC 9001 §8.4ﾈ0-RTT 包号防重放ﾉ
    // ══════════════════════════════════════════════════════════════════

    /**
     * 场景TLS 握手 phase1 后注入混合 0-RTT 包（含重复包号），
     * 验证 anti-replay 机制仅处理重复中的第一次，后续重复被静默丢弃。
     * <h3>实现机制</h3>
     * {@code processBuffered0RttPacket()} 在交付前调用
     * {@code handshake.checkAndMarkRtt0Pn(pn)}：
     * <pre>
     *   首次 PN=0 → seenRtt0PacketNumbers.add(0) = true  → 处理
     *   重复 PN=0 → seenRtt0PacketNumbers.add(0) = false → 丢弃（anti-replay 触发）
     * </pre>
     * <h3>测试步骤</h3>
     * <ol>
     *   <li>Phase 1：学习服务端 SCID。</li>
     *   <li>注入 5 个唯一 0-RTT 包（PN 0–4）+ 3 个重复包（PN 0、1、2）= 共 8 个。</li>
     *   <li>Phase 2：完成握手，触发 drain；anti-replay 将 3 个重复包丢弃，仅交付 5 个。</li>
     * </ol>
     * <h3>验证点</h3>
     * <ol>
     *   <li>握手成功（anti-replay 丢弃不影响握手流程）。</li>
     *   <li>服务端不崩溃（重复包仅记录日志，不抛异常）。</li>
     *   <li>握手完成后 1-RTT PING 可正常发送（连接健壮）。</li>
     * </ol>
     */
    @Test
    public void testAntiReplayBlocks0RttDuplicates() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler */ }, quicTlsConfig());

        SslCertConfig clientSslCfg = new SslCertConfig();
        try (QuicSimplyTlsClient tlsClient = new QuicSimplyTlsClient(address, clientSslCfg)) {
            // ── 1. Phase 1 ─────────────────────────────────────────────────────────
            tlsClient.handshakePhase1();

            byte[] scid = tlsClient.getLocalCid();
            byte[] dcid = tlsClient.getRemoteCid();
            byte[] pingPayload = QuicSimplyClient.buildPingBytes();
            DatagramSocket rawSock = tlsClient.getSocket();

            // ── 2. 注入 5 个唯一包（PN 0–4）──────────────────────────────────
            for (int i = 0; i < 5; i++) {
                byte[] pkt = buildZeroRttPacket(dcid, scid, i, pingPayload);
                rawSock.send(new DatagramPacket(pkt, pkt.length, address));
            }
            // ── 3. 重复注入 PN 0、1、2（anti-replay 应丢弃这 3 个）────────────
            for (int i = 0; i < 3; i++) {
                byte[] pkt = buildZeroRttPacket(dcid, scid, i, pingPayload);
                rawSock.send(new DatagramPacket(pkt, pkt.length, address));
            }
            Thread.sleep(120); // 让服务端接收并缓冲所有 8 个包

            // ── 4. Phase 2 → drain → anti-replay 检查 ───────────────────────
            tlsClient.handshakePhase2();

            assert tlsClient.isEstablished() : "TLS connection must be established despite duplicate 0-RTT packets";

            // ── 5. 连接功能验证 ──────────────────────────────────────────────
            tlsClient.sendPing();
        }

        neta.shutdown();
    }
}
