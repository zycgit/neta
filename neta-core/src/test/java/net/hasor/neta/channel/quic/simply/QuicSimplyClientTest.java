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
package net.hasor.neta.channel.quic.simply;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import org.junit.Test;

/**
 * {@link QuicSimplyClient} 冒烟测试。
 * <p>
 * 验证裸 QUIC 客户端能够与 neta QUIC 服务端完成明文握手并发送各类帧，
 * 为后续注入式模拟测试（错误路径、状态机边界等）提供基础保障。
 * @author Copilot / 赵永春 (zyc@hasor.net)
 */
public class QuicSimplyClientTest extends AbstractSoTest {

    /**
     * 验证明文握手能正常完成：{@link QuicSimplyClient#handshake()} 不抛异常即为成功。
     * 握手后服务端连接映射中已注册该连接，客户端 {@code remoteCid} 已更新为服务端 localCid。
     */
    @Test
    public void testHandshakeCompletes() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> { /* 无需任何 handler */ }, quicConfig());

        try (QuicSimplyClient client = new QuicSimplyClient(address)) {
            client.handshake();
            // remoteCid 在握手后必须已被更新为服务端的 localCid（非空非零）
            byte[] remoteCid = client.getRemoteCid();
            assert remoteCid != null && remoteCid.length == QuicSimplyClient.DEFAULT_CID_LEN : "remoteCid should be " + QuicSimplyClient.DEFAULT_CID_LEN + " bytes after handshake";
        }

        neta.shutdown();
    }

    /**
     * 握手后发送 PING 帧：验证 1-RTT 短头包能被服务端正常接收并回复 ACK。
     * 客户端通过 {@link QuicSimplyClient#receive1RttPayload(int)} 读取 ACK 包，不抛异常即为成功。
     */
    @Test
    public void testSendPingAndReceiveAck() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> { /* 无需任何 handler */ }, quicConfig());

        try (QuicSimplyClient client = new QuicSimplyClient(address)) {
            client.handshake();
            client.sendPing();
            // 服务端收到 PING（ack-eliciting 帧）后会回 ACK；等待 1-RTT 包
            byte[] ackPayload = client.receive1RttPayload(3000);
            assert ackPayload != null : "Expected ACK response after PING, but got timeout";
        }

        neta.shutdown();
    }

    /**
     * 握手后向 stream 0（client 发起 bidi 流）发送 STREAM 帧，验证不抛异常。
     * stream 0 是 client 发起的第一条双向流，服务端会自动创建对应的 QuicStreamChannel。
     */
    @Test
    public void testSendStreamFrame() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> { /* 无需任何 handler */ }, quicConfig());

        try (QuicSimplyClient client = new QuicSimplyClient(address)) {
            client.handshake();
            byte[] data = "hello from raw client\n".getBytes(StandardCharsets.UTF_8);
            // stream 0 = client-initiated bidi（bit0=0 client, bit1=0 bidi）
            client.sendStreamFrame(0L, 0L, data, false);
        }

        neta.shutdown();
    }

    /**
     * 验证 {@link QuicSimplyClient#concat(byte[][])} 能将多帧拼接后通过单次 UDP 发送出去。
     * 同时验证 CONNECTION_CLOSE 帧序列化与发送均不抛异常。
     */
    @Test
    public void testSendMultipleFramesInOnePacket() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, ctx -> { /* 无需任何 handler */ }, quicConfig());

        try (QuicSimplyClient client = new QuicSimplyClient(address)) {
            client.handshake();
            // 拼装：PING + STREAM 两帧合并为一个 1-RTT 包
            byte[] streamData = "world\n".getBytes(StandardCharsets.UTF_8);
            byte[] multi = QuicSimplyClient.concat(QuicSimplyClient.buildPingBytes(), QuicSimplyClient.buildStreamFrame(0L, 0L, streamData, false));
            client.sendFrame(multi);
        }

        neta.shutdown();
    }

    /**
     * 验证帧构造工具方法的正确性（纯静态、无网络）：
     * <ul>
     *   <li>VarInt 编解码对称性</li>
     *   <li>STREAM 帧首字节标志位</li>
     *   <li>Long Header 首字节</li>
     *   <li>Short Header 首字节</li>
     * </ul>
     */
    @Test
    public void testFrameBuilderCorrectness() {
        // ── VarInt 编解码 ────────────────────────────────────────
        long[] cases = { 0, 1, 63, 64, 16383, 16384, 1073741823L, 1073741824L };
        for (long v : cases) {
            byte[] encoded = QuicSimplyClient.encodeVarInt(v);
            long[] decoded = QuicSimplyClient.decodeVarInt(encoded, 0);
            assert decoded[0] == v : "VarInt round-trip failed for " + v + ": got " + decoded[0];
            assert decoded[1] == encoded.length : "VarInt bytesRead wrong for " + v;
        }

        // ── STREAM 帧首字节（bit3=1 STREAM_BASE, bit2=1 OFF, bit1=1 LEN）──
        byte[] streamFrame = QuicSimplyClient.buildStreamFrame(0, 0, new byte[] { 1, 2, 3 }, false);
        int streamType = streamFrame[0] & 0xFF;
        assert (streamType & 0x08) != 0 : "STREAM frame type bit3 should be set";
        assert (streamType & 0x04) != 0 : "STREAM frame OFF bit should be set";
        assert (streamType & 0x02) != 0 : "STREAM frame LEN bit should be set";
        assert (streamType & 0x01) == 0 : "STREAM frame FIN bit should be clear when fin=false";

        byte[] streamFin = QuicSimplyClient.buildStreamFrame(0, 0, new byte[0], true);
        assert (streamFin[0] & 0x01) != 0 : "STREAM frame FIN bit should be set when fin=true";

        // ── Long Header 首字节（Initial: 0xC0）──────────────────
        byte[] longPkt = QuicSimplyClient.buildRawLongHeaderPacket(QuicSimplyClient.TYPE_INITIAL, QuicSimplyClient.QUIC_V1, new byte[8], new byte[8], new byte[0], 0, new byte[1]);
        assert (longPkt[0] & 0xFF) == 0xC0 : "Initial long header first byte should be 0xC0, got 0x" + Integer.toHexString(longPkt[0] & 0xFF);
        assert QuicSimplyClient.isLongHeader(longPkt) : "isLongHeader should return true";

        // ── Short Header 首字节（0x40 | pnLen-1）───────────────
        byte[] shortPkt = QuicSimplyClient.buildRaw1RttPacket(new byte[8], 0, new byte[] { 0x01 });
        assert (shortPkt[0] & 0xFF) == 0x40 : "Short header first byte should be 0x40, got 0x" + Integer.toHexString(shortPkt[0] & 0xFF);
        assert !QuicSimplyClient.isLongHeader(shortPkt) : "isLongHeader should return false for short header";
    }
}
