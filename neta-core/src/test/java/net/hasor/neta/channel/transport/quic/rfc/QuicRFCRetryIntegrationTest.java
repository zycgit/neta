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
package net.hasor.neta.channel.transport.quic.rfc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

import org.junit.Test;

import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.quic.QuicSoConfig;
import net.hasor.neta.channel.transport.quic.simply.QuicSimplyClient;

/**
 * QUIC RFC 合规测试 — Retry 流程端到端（RFC 9000 §17.2.5 / §8.1 + RFC 9001 §5.8）。
 * <p>
 * 本测试覆盖服务端侧的 Retry 触发与 Retry 包线协议结构，用于在代码层面防回归
 * {@code QuicAsyncChannelHandshake.processRetry} 与 {@code QuicAsyncServerChannel.sendRetryPacket}：
 * <ul>
 *   <li>R1-1：带非空无效 token 的 Initial 包触发服务端 Retry 响应。</li>
 *   <li>R1-2：响应包确为 QUIC Retry（Long Header + Fixed Bit + Retry 类型位）。</li>
 *   <li>R1-3：Retry 字段结构符合 RFC 9000 §17.2.5（version / DCID / SCID / Token / 16 字节 Tag）。</li>
 *   <li>R1-4：Retry 的 DCID 回显客户端 Initial 的 SCID（§17.2.5.2 客户端校验前提）。</li>
 *   <li>R1-5：Retry 的 SCID 非空、Token 非空、16 字节 Integrity Tag 存在（§5.8 线约束）。</li>
 *   <li>R1-6：空 token 的 Initial 不应触发 Retry（防误回归）。</li>
 * </ul>
 * <p>
 * 本测试位于 {@code rfc/} 子包，只依赖 {@link QuicSimplyClient} 中对外公开的线格式构造工具
 * 和手写的 Retry 解析，不接触包私有的 {@code QuicPacket} 等主代码内部类型。
 * Retry Integrity Tag 的 AEAD 加密向量合规性在父包 {@code QuicRFCRetryTagTest} 中通过
 * RFC 9001 Appendix A.4 测试向量校验。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCRetryIntegrationTest extends AbstractSoTest {

    /** 生成指定长度的随机字节数组（作为无效 token / 连接 ID 用）。 */
    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * 手写的 RFC 9000 §17.2.5 Retry 包结构，不依赖主代码包私有类型。
     * <pre>
     *   Retry Packet {
     *     Header Form (1) = 1,
     *     Fixed Bit (1) = 1,
     *     Long Packet Type (2) = Retry (v1 为 0b11),
     *     Unused (4),
     *     Version (32),
     *     DCID Length (8), DCID (0..160),
     *     SCID Length (8), SCID (0..160),
     *     Retry Token (..),
     *     Retry Integrity Tag (128),
     *   }
     * </pre>
     */
    private static final class ParsedRetry {
        int    version;
        byte[] dcid;
        byte[] scid;
        byte[] token;
        byte[] integrityTag;
    }

    /** 判断是否是 QUIC v1 Retry 包：Long Header + Fixed Bit + 类型位 0b11 + 版本 == 0x00000001。 */
    private static boolean isV1RetryPacket(byte[] data) {
        if (data == null || data.length < 1 + 4 + 1 + 1 + 16) {
            return false;
        }
        if ((data[0] & 0x80) == 0 || (data[0] & 0x40) == 0) {
            return false;
        }
        int version = ((data[1] & 0xFF) << 24) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        if (version != QuicSimplyClient.QUIC_V1) {
            return false;
        }
        int wireType = (data[0] & 0x30) >> 4; // v1 Retry wire type = 0b11
        return wireType == 0x03;
    }

    /** 按 RFC 9000 §17.2.5 解析 Retry 包；仅支持 v1（测试服务端也是 v1）。 */
    private static ParsedRetry parseV1Retry(byte[] data) {
        if (!isV1RetryPacket(data)) {
            return null;
        }
        int pos = 1;
        int version = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) //
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        int dcidLen = data[pos++] & 0xFF;
        if (pos + dcidLen >= data.length) {
            return null;
        }
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(data, pos, dcid, 0, dcidLen);
        pos += dcidLen;
        int scidLen = data[pos++] & 0xFF;
        if (pos + scidLen > data.length) {
            return null;
        }
        byte[] scid = new byte[scidLen];
        System.arraycopy(data, pos, scid, 0, scidLen);
        pos += scidLen;
        int tagOffset = data.length - 16;
        if (tagOffset < pos) {
            return null;
        }
        byte[] token = new byte[tagOffset - pos];
        System.arraycopy(data, pos, token, 0, token.length);
        byte[] tag = new byte[16];
        System.arraycopy(data, tagOffset, tag, 0, 16);
        ParsedRetry r = new ParsedRetry();
        r.version = version;
        r.dcid = dcid;
        r.scid = scid;
        r.token = token;
        r.integrityTag = tag;
        return r;
    }

    // ════════════════════════════════════════════════════════════════════
    //  R1: 服务端 Retry 行为 — RFC 9000 §8.1 地址校验 + §17.2.5 Retry 格式
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：客户端向服务端发送一个 Initial 包，其中携带非空但无效的 token。
     * <p>
     * 服务端应按 RFC 9000 §8.1.2 回执一个 Retry 包（而非直接接受连接），
     * 且其线格式必须遵循 §17.2.5 和 RFC 9001 §5.8。
     */
    @Test
    public void testServerSendsRetryForBadToken() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        QuicSoConfig cfg = quicConfig();
        neta.bind(address, ctx -> {
            /* no handler needed */ }, cfg);
        try {
            byte[] serverDcid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN); // 服务端未知 DCID → 新连接
            byte[] clientScid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
            byte[] badToken = randomBytes(16); // 非空且无效（未经过服务端签发）
            byte[] cryptoFrame = QuicSimplyClient.buildCryptoFrame(0, new byte[0]);
            byte[] initial = QuicSimplyClient.buildRawLongHeaderPacket(//
                    QuicSimplyClient.TYPE_INITIAL, QuicSimplyClient.QUIC_V1,//
                    serverDcid, clientScid, badToken, 0L, cryptoFrame);

            byte[] response;
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(3000);
                sock.send(new DatagramPacket(initial, initial.length, address));

                byte[] buf = new byte[65535];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                response = new byte[pkt.getLength()];
                System.arraycopy(buf, 0, response, 0, pkt.getLength());
            }

            // ── R1-1 / R1-2: 响应必须是 QUIC Retry 包 ────────────────────────────
            assert response.length >= 1 + 4 + 1 + 1 + 16 //
                    : "Retry response too short: got " + response.length + " bytes (min = header+version+dcidLen+scidLen+tag)";
            assert (response[0] & 0x80) != 0 : "Retry first byte must have Header Form bit set (Long Header)";
            assert (response[0] & 0x40) != 0 : "Retry first byte must have Fixed Bit set (RFC 9000 §17.2)";
            assert isV1RetryPacket(response) //
                    : "Server must respond with a QUIC v1 Retry packet when Initial carries non-empty invalid token";

            // ── R1-3: 能按 RFC 9000 §17.2.5 结构完整解析 ─────────────────────────
            ParsedRetry retry = parseV1Retry(response);
            assert retry != null : "Retry response must be parseable per RFC 9000 §17.2.5";
            assert retry.version == QuicSimplyClient.QUIC_V1 : "Retry version must be QUIC v1";

            // ── R1-4: DCID 回显客户端 Initial 的 SCID（§17.2.5.2 客户端校验前提）──
            assert retry.dcid != null && retry.dcid.length == clientScid.length //
                    : "Retry DCID length must equal client's Initial SCID length";
            for (int i = 0; i < clientScid.length; i++) {
                assert retry.dcid[i] == clientScid[i] //
                        : "Retry DCID must echo client's Initial SCID at byte " + i;
            }

            // ── R1-5: SCID 非空 / Token 非空 / Integrity Tag 16 字节 ──────────────
            assert retry.scid != null && retry.scid.length > 0 //
                    : "Retry SCID must be non-empty per RFC 9000 §17.2.5";
            assert retry.token != null && retry.token.length > 0 //
                    : "Retry Token must be non-empty per RFC 9000 §17.2.5";
            assert retry.integrityTag != null && retry.integrityTag.length == 16 //
                    : "Retry Integrity Tag must be 16 bytes per RFC 9001 §5.8";
        } finally {
            neta.shutdown();
        }
    }

    /**
     * 场景：客户端发送一个空 token 的合法 Initial，服务端不应发送 Retry
     * （neta 默认不强制首包 Retry-before-Initial）。防回归服务端把 Retry 发给每个连接。
     */
    @Test
    public void testServerDoesNotRetryOnEmptyToken() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> {
            /* no handler needed */ }, quicConfig());
        try {
            byte[] serverDcid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
            byte[] clientScid = randomBytes(QuicSimplyClient.DEFAULT_CID_LEN);
            byte[] cryptoFrame = QuicSimplyClient.buildCryptoFrame(0, new byte[0]);
            byte[] initial = QuicSimplyClient.buildRawLongHeaderPacket(//
                    QuicSimplyClient.TYPE_INITIAL, QuicSimplyClient.QUIC_V1,//
                    serverDcid, clientScid, new byte[0], 0L, cryptoFrame);

            byte[] response;
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(3000);
                sock.send(new DatagramPacket(initial, initial.length, address));
                byte[] buf = new byte[65535];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                response = new byte[pkt.getLength()];
                System.arraycopy(buf, 0, response, 0, pkt.getLength());
            }

            assert !isV1RetryPacket(response) //
                    : "Server must not send Retry for an Initial with empty token";
        } finally {
            neta.shutdown();
        }
    }
}
