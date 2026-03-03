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
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级裸 QUIC 客户端，专为模拟测试设计。
 * <p>
 * 仅支持<b>明文模式</b>（非 TLS，对应服务端 {@code isSslEnabled=false}），
 * 可精确控制每一条发送的 QUIC 帧，便于测试各类异常路径。
 * <h3>握手流程（明文）</h3>
 * <pre>
 *   Client → Server : Initial( CRYPTO{empty} )
 *   Server → Client : Initial( ACK + CRYPTO{empty} )
 *   ——— 两端均进入 ESTABLISHED，连接就绪 ———
 * </pre>
 * <h3>典型用法</h3>
 * <pre>{@code
 * try (QuicSimplyClient client = new QuicSimplyClient(serverAddress)) {
 *     client.handshake();
 *     // 向 stream 0（client 发起 bidi）写数据
 *     client.sendStreamFrame(0, 0, "hello\n".getBytes(StandardCharsets.UTF_8), false);
 *     // 注入非法帧以测试服务端错误检测
 *     client.sendFrame(QuicSimplyClient.buildConnectionCloseFrame(0x05, 0, "test"));
 * }
 * }</pre>
 * <h3>流 ID 编码（RFC 9000 §2.1）</h3>
 * <ul>
 *   <li>bit 0 = 0 → client 发起；= 1 → server 发起</li>
 *   <li>bit 1 = 0 → 双向（bidi）；= 1 → 单向（uni）</li>
 *   <li>client bidi: 0, 4, 8, …；client uni: 2, 6, 10, …</li>
 *   <li>server bidi: 1, 5, 9, …；server uni: 3, 7, 11, …</li>
 * </ul>
 * @author Copilot / 赵永春 (zyc@hasor.net)
 */
public class QuicSimplyClient implements Closeable {

    // ── QUIC 常量 ──────────────────────────────────────────────────────
    /** QUIC v1 wire version (RFC 9000). */
    public static final int QUIC_V1         = 0x00000001;
    /** Long Header 包：Initial 类型（wire bits 4-5 = 0b00）。 */
    public static final int TYPE_INITIAL    = 0x00;
    /** Long Header 包：Handshake 类型（wire bits 4-5 = 0b10）。 */
    public static final int TYPE_HANDSHAKE  = 0x02;
    /** 默认连接 ID 长度（与服务端 quicConfig().getConnectionIdLength() = 8 对齐）。 */
    public static final int DEFAULT_CID_LEN = 8;

    // ── 错误码（RFC 9000 §20.1）────────────────────────────────────────
    /** STREAM_STATE_ERROR: 在不合法的流状态下收到帧。 */
    public static final long ERR_STREAM_STATE_ERROR = 0x05L;
    /** FLOW_CONTROL_ERROR: 发送数据超过流量控制限制。 */
    public static final long ERR_FLOW_CONTROL_ERROR = 0x03L;
    /** PROTOCOL_VIOLATION: 通用协议违规。 */
    public static final long ERR_PROTOCOL_VIOLATION = 0x0AL;

    // ── 实例字段 ───────────────────────────────────────────────────────
    /** 我方（client）使用的 Connection ID，在发送的 Initial SCID 中携带。 */
    private final byte[]            localCid;
    /** 发包 PN 单调递增计数器（全局，不区分包类型）。 */
    private final AtomicLong        sendPn = new AtomicLong(0);
    /** 底层 UDP 套接字。 */
    private final DatagramSocket    socket;
    /** 服务端地址。 */
    private final InetSocketAddress serverAddr;
    /**
     * 对端（server）的 Connection ID。
     * 握手前：我方随机生成的初始 DCID；握手后：从服务端 Initial 的 SCID 字段更新。
     */
    private       byte[]            remoteCid;

    // ── 构造 ───────────────────────────────────────────────────────────

    /**
     * 创建一个裸 QUIC 客户端，绑定到本地随机 UDP 端口。
     * @param serverAddr 要连接的服务端地址
     * @throws IOException 套接字创建失败
     */
    public QuicSimplyClient(InetSocketAddress serverAddr) throws IOException {
        this.serverAddr = serverAddr;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5000);
        this.localCid = randomBytes(DEFAULT_CID_LEN);
        this.remoteCid = randomBytes(DEFAULT_CID_LEN);
    }

    // ── 握手 ───────────────────────────────────────────────────────────

    /**
     * 构造 STREAM 帧（RFC 9000 §19.8）。
     * 始终包含 OFF 位（offset 字段）和 LEN 位（length 字段），便于对端解析。
     * <pre>
     * STREAM 帧格式:
     *   varint(0x08 | OFF | LEN | FIN)
     *   varint(Stream ID)
     *   varint(Offset)    ← OFF 位置位时出现
     *   varint(Length)    ← LEN 位置位时出现
     *   data bytes
     * </pre>
     */
    public static byte[] buildStreamFrame(long streamId, long offset, byte[] data, boolean fin) {
        int flags = 0x08 | 0x04 | 0x02; // STREAM_BASE | OFF | LEN
        if (fin) {
            flags |= 0x01;
        }
        byte[] typeB = encodeVarInt(flags);
        byte[] sidB = encodeVarInt(streamId);
        byte[] offB = encodeVarInt(offset);
        byte[] lenB = encodeVarInt(data.length);
        byte[] frame = new byte[typeB.length + sidB.length + offB.length + lenB.length + data.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(sidB, frame, pos);
        pos = copy(offB, frame, pos);
        pos = copy(lenB, frame, pos);
        copy(data, frame, pos);
        return frame;
    }

    // ── 帧发送 ─────────────────────────────────────────────────────────

    /**
     * 构造 CRYPTO 帧（RFC 9000 §19.6）。
     * 握手时由 {@link #handshake()} 内部调用；也可用于注入 TLS 相关测试。
     */
    public static byte[] buildCryptoFrame(long offset, byte[] data) {
        byte[] typeB = encodeVarInt(0x06);
        byte[] offB = encodeVarInt(offset);
        byte[] lenB = encodeVarInt(data.length);
        byte[] frame = new byte[typeB.length + offB.length + lenB.length + data.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(offB, frame, pos);
        pos = copy(lenB, frame, pos);
        copy(data, frame, pos);
        return frame;
    }

    /**
     * 构造 ACK 帧（RFC 9000 §19.3，简化版：单段、无 Gap）。
     * @param largestAcked 已确认的最大 PN
     * @param ackDelay ACK 延迟（可传 0）
     * @param firstRange 第一段 ACK 长度（= largestAcked - firstUnacked）
     */
    public static byte[] buildAckFrame(long largestAcked, long ackDelay, long firstRange) {
        byte[] typeB = encodeVarInt(0x02);
        byte[] largeB = encodeVarInt(largestAcked);
        byte[] delayB = encodeVarInt(ackDelay);
        byte[] cntB = encodeVarInt(0); // ACK Range Count = 0
        byte[] firstB = encodeVarInt(firstRange);
        byte[] frame = new byte[typeB.length + largeB.length + delayB.length + cntB.length + firstB.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(largeB, frame, pos);
        pos = copy(delayB, frame, pos);
        pos = copy(cntB, frame, pos);
        copy(firstB, frame, pos);
        return frame;
    }

    /**
     * 构造 CONNECTION_CLOSE 帧（RFC 9000 §19.19，类型 0x1c）。
     * @param errorCode QUIC 错误码
     * @param triggerFrameType 触发本错误的帧类型，未知时填 0
     * @param reason 可读原因字符串（空串时不携带 Reason Phrase）
     */
    public static byte[] buildConnectionCloseFrame(long errorCode, long triggerFrameType, String reason) {
        byte[] reasonBytes = (reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8);
        byte[] typeB = encodeVarInt(0x1c);
        byte[] errB = encodeVarInt(errorCode);
        byte[] ftB = encodeVarInt(triggerFrameType);
        byte[] rlenB = encodeVarInt(reasonBytes.length);
        byte[] frame = new byte[typeB.length + errB.length + ftB.length + rlenB.length + reasonBytes.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(errB, frame, pos);
        pos = copy(ftB, frame, pos);
        pos = copy(rlenB, frame, pos);
        copy(reasonBytes, frame, pos);
        return frame;
    }

    /**
     * 构造 RESET_STREAM 帧（RFC 9000 §19.4）。
     * @param streamId 要重置的流 ID
     * @param errorCode 应用层错误码
     * @param finalSize 该流已发送的最终字节偏移
     */
    public static byte[] buildResetStreamFrame(long streamId, long errorCode, long finalSize) {
        byte[] typeB = encodeVarInt(0x04);
        byte[] sidB = encodeVarInt(streamId);
        byte[] errB = encodeVarInt(errorCode);
        byte[] sizeB = encodeVarInt(finalSize);
        byte[] frame = new byte[typeB.length + sidB.length + errB.length + sizeB.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(sidB, frame, pos);
        pos = copy(errB, frame, pos);
        copy(sizeB, frame, pos);
        return frame;
    }

    // ── 接收 ───────────────────────────────────────────────────────────

    /**
     * 构造 STOP_SENDING 帧（RFC 9000 §19.5）。
     * @param streamId 目标流 ID
     * @param errorCode 应用层错误码
     */
    public static byte[] buildStopSendingFrame(long streamId, long errorCode) {
        byte[] typeB = encodeVarInt(0x05);
        byte[] sidB = encodeVarInt(streamId);
        byte[] errB = encodeVarInt(errorCode);
        byte[] frame = new byte[typeB.length + sidB.length + errB.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(sidB, frame, pos);
        copy(errB, frame, pos);
        return frame;
    }

    /**
     * 构造 MAX_STREAM_DATA 帧（RFC 9000 §19.10）。
     * @param streamId 目标流 ID
     * @param maxData 新的流级别最大字节数
     */
    public static byte[] buildMaxStreamDataFrame(long streamId, long maxData) {
        byte[] typeB = encodeVarInt(0x11);
        byte[] sidB = encodeVarInt(streamId);
        byte[] maxB = encodeVarInt(maxData);
        byte[] frame = new byte[typeB.length + sidB.length + maxB.length];
        int pos = 0;
        pos = copy(typeB, frame, pos);
        pos = copy(sidB, frame, pos);
        copy(maxB, frame, pos);
        return frame;
    }

    // ── 属性读取 ───────────────────────────────────────────────────────

    /**
     * 将多个帧字节数组拼接为单一字节数组，用于一次发送多帧。
     * <pre>{@code
     * byte[] multi = QuicSimplyClient.concat(
     *     QuicSimplyClient.buildPingBytes(),
     *     QuicSimplyClient.buildStreamFrame(0, 0, data, false)
     * );
     * client.sendFrame(multi);
     * }</pre>
     */
    public static byte[] concat(byte[]... frames) {
        int total = 0;
        for (byte[] f : frames) {
            total += f.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] f : frames) {
            pos = copy(f, result, pos);
        }
        return result;
    }

    /** 返回 PING 帧的字节表示（单字节 0x01）。 */
    public static byte[] buildPingBytes() {
        return encodeVarInt(0x01);
    }

    /**
     * 构造明文 Long Header 包（Initial / Handshake，无 AEAD 加密）。
     * <pre>
     * 包格式（RFC 9000 §17.2）:
     *   1B  : 0xC0 | (packetType << 4) | (pnLength - 1)
     *   4B  : QUIC 版本（大端）
     *   1B  : DCID 长度
     *   NB  : DCID
     *   1B  : SCID 长度
     *   MB  : SCID
     *   [Initial 专有] varint: token 长度 + token 字节
     *   varint : Length = pnLength + payloadLength
     *   pnLength B : 包序号（大端截断）
     *   payloadLength B : 帧内容
     * </pre>
     * @param packetType 包类型（{@link #TYPE_INITIAL} 或 {@link #TYPE_HANDSHAKE}）
     * @param version QUIC 版本（通常传 {@link #QUIC_V1}）
     * @param dcid 目标连接 ID
     * @param scid 源连接 ID
     * @param token Initial 令牌（非 Initial 类型传空数组即可）
     * @param packetNumber 包序号
     * @param payload 帧字节（已序列化）
     */
    public static byte[] buildRawLongHeaderPacket(int packetType, int version, byte[] dcid, byte[] scid, byte[] token, long packetNumber, byte[] payload) {
        if (token == null) {
            token = new byte[0];
        }
        int pnLength = packetNumberLength(packetNumber);
        int lengthFieldValue = pnLength + payload.length;
        byte[] lenVarInt = encodeVarInt(lengthFieldValue);

        // 头部大小计算
        int headerSize = 1 + 4           // firstByte + version
                + 1 + dcid.length        // dcidLen + dcid
                + 1 + scid.length;       // scidLen + scid
        if (packetType == TYPE_INITIAL) {
            headerSize += varIntLength(token.length) + token.length; // tokenLen + token
        }
        headerSize += lenVarInt.length;  // length field

        byte[] packet = new byte[headerSize + pnLength + payload.length];
        int pos = 0;

        // first byte
        packet[pos++] = (byte) (0xC0 | ((packetType & 0x03) << 4) | (pnLength - 1));
        // version (big-endian)
        packet[pos++] = (byte) ((version >> 24) & 0xFF);
        packet[pos++] = (byte) ((version >> 16) & 0xFF);
        packet[pos++] = (byte) ((version >> 8) & 0xFF);
        packet[pos++] = (byte) (version & 0xFF);
        // DCID
        packet[pos++] = (byte) dcid.length;
        pos = copy(dcid, packet, pos);
        // SCID
        packet[pos++] = (byte) scid.length;
        pos = copy(scid, packet, pos);
        // token (Initial only)
        if (packetType == TYPE_INITIAL) {
            pos = encodeVarIntTo(packet, pos, token.length);
            pos = copy(token, packet, pos);
        }
        // length
        pos = copy(lenVarInt, packet, pos);
        // packet number（大端截断）
        for (int i = pnLength - 1; i >= 0; i--) {
            packet[pos + i] = (byte) (packetNumber & 0xFF);
            packetNumber >>= 8;
        }
        pos += pnLength;
        // payload
        copy(payload, packet, pos);
        return packet;
    }

    /**
     * 构造明文 1-RTT 短头包（无 AEAD 加密）。
     * <pre>
     * 包格式（RFC 9000 §17.3）:
     *   1B  : 0x40 | (pnLength - 1)
     *   NB  : DCID（固定长度，与服务端配置一致）
     *   pnLength B : 包序号（大端截断）
     *   rest B : 帧内容
     * </pre>
     */
    public static byte[] buildRaw1RttPacket(byte[] dcid, long packetNumber, byte[] payload) {
        int pnLength = packetNumberLength(packetNumber);
        byte[] packet = new byte[1 + dcid.length + pnLength + payload.length];
        int pos = 0;
        packet[pos++] = (byte) (0x40 | (pnLength - 1));
        pos = copy(dcid, packet, pos);
        long pn = packetNumber;
        for (int i = pnLength - 1; i >= 0; i--) {
            packet[pos + i] = (byte) (pn & 0xFF);
            pn >>= 8;
        }
        pos += pnLength;
        copy(payload, packet, pos);
        return packet;
    }

    // ==================== 帧构造工具方法（均为静态，可独立使用）====================

    /**
     * 将值编码为 QUIC 可变长整数字节数组。
     * <pre>
     * 2-bit 前缀指示长度：
     *   00 → 1 byte  (最大 63)
     *   01 → 2 bytes (最大 16383)
     *   10 → 4 bytes (最大 1073741823)
     *   11 → 8 bytes (最大 4611686018427387903)
     * </pre>
     */
    public static byte[] encodeVarInt(long value) {
        if (value <= 0x3FL) {
            return new byte[] { (byte) value };
        } else if (value <= 0x3FFFL) {
            return new byte[] { (byte) (0x40 | (value >> 8)), (byte) (value & 0xFF) };
        } else if (value <= 0x3FFFFFFFL) {
            return new byte[] { (byte) (0x80 | (value >> 24)), (byte) ((value >> 16) & 0xFF), (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF) };
        } else {
            return new byte[] { (byte) (0xC0 | (value >> 56)), (byte) ((value >> 48) & 0xFF), (byte) ((value >> 40) & 0xFF), (byte) ((value >> 32) & 0xFF), (byte) ((value >> 24) & 0xFF), (byte) ((value >> 16) & 0xFF), (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF) };
        }
    }

    /**
     * 将 VarInt 直接编码到目标数组的指定位置，返回写入后的新偏移量。
     */
    public static int encodeVarIntTo(byte[] dst, int offset, long value) {
        byte[] encoded = encodeVarInt(value);
        System.arraycopy(encoded, 0, dst, offset, encoded.length);
        return offset + encoded.length;
    }

    /**
     * 计算编码指定值所需的 VarInt 字节数（不实际编码）。
     */
    public static int varIntLength(long value) {
        if (value <= 0x3FL)
            return 1;
        if (value <= 0x3FFFL)
            return 2;
        if (value <= 0x3FFFFFFFL)
            return 4;
        return 8;
    }

    /**
     * 解码 QUIC VarInt，返回 {@code long[]{value, bytesRead}}。
     * @param data 包含编码数据的字节数组
     * @param offset 起始偏移
     * @return {@code [value, bytesRead]}
     */
    public static long[] decodeVarInt(byte[] data, int offset) {
        if (offset >= data.length) {
            return new long[] { 0, 0 };
        }
        int prefix = (data[offset] & 0xC0) >> 6;
        switch (prefix) {
            case 0:
                return new long[] { data[offset] & 0x3FL, 1 };
            case 1: {
                long v = ((data[offset] & 0x3FL) << 8) | (data[offset + 1] & 0xFFL);
                return new long[] { v, 2 };
            }
            case 2: {
                long v = ((data[offset] & 0x3FL) << 24) | ((data[offset + 1] & 0xFFL) << 16) | ((data[offset + 2] & 0xFFL) << 8) | (data[offset + 3] & 0xFFL);
                return new long[] { v, 4 };
            }
            default: {
                long v = ((data[offset] & 0x3FL) << 56) | ((data[offset + 1] & 0xFFL) << 48) | ((data[offset + 2] & 0xFFL) << 40) | ((data[offset + 3] & 0xFFL) << 32) | ((data[offset + 4] & 0xFFL) << 24) | ((data[offset + 5] & 0xFFL) << 16) | ((data[offset + 6] & 0xFFL) << 8) | (data[offset + 7] & 0xFFL);
                return new long[] { v, 8 };
            }
        }
    }

    /** 判断首字节最高位是否为 1（Long Header 标志）。 */
    public static boolean isLongHeader(byte[] data) {
        return data.length > 0 && (data[0] & 0x80) != 0;
    }

    private static byte[] randomBytes(int length) {
        byte[] b = new byte[length];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** 拷贝 src 到 dst[dstOffset]，返回新偏移量。 */
    private static int copy(byte[] src, byte[] dst, int dstOffset) {
        if (src.length == 0) {
            return dstOffset;
        }
        System.arraycopy(src, 0, dst, dstOffset, src.length);
        return dstOffset + src.length;
    }

    /**
     * 计算包序号所需的字节数（最少 1 字节，简化版：始终 1 字节，适用于 PN < 256）。
     * 若 PN 超出 255 则升为 2 字节，超出 65535 升为 4 字节。
     */
    private static int packetNumberLength(long pn) {
        if (pn <= 0xFF)
            return 1;
        if (pn <= 0xFFFF)
            return 2;
        return 4;
    }

    /**
     * 执行明文 QUIC 握手（non-TLS 模式）。
     * <p>
     * 发送 Initial 包后等待服务端的 Initial 响应，从中提取服务端的
     * 连接 ID（SCID）更新为后续 1-RTT 短头包的目标 DCID。
     * 方法返回后连接进入 ESTABLISHED 状态，可立即调用帧发送方法。
     * @throws IOException 握手超时或 UDP 通信异常
     */
    public void handshake() throws IOException {
        // ── 1. 发送 client Initial（空 CRYPTO 帧）──────────────────────
        byte[] cryptoFrame = buildCryptoFrame(0, new byte[0]);
        byte[] initialPacket = buildRawLongHeaderPacket(TYPE_INITIAL, QUIC_V1, remoteCid, localCid, new byte[0], sendPn.getAndIncrement(), cryptoFrame);
        send(initialPacket);

        // ── 2. 接收服务端 Initial 响应，提取 server localCid ──────────
        byte[] serverResponse = receiveRaw(5000);
        if (serverResponse == null) {
            throw new IOException("QUIC handshake timeout: no Initial response from server");
        }
        if (isLongHeader(serverResponse)) {
            // 格式：1B firstByte + 4B version + 1B dcidLen + dcid + 1B scidLen + scid + …
            int pos = 5; // 跳过 firstByte(1) + version(4)
            int dcidLen = serverResponse[pos++] & 0xFF;
            pos += dcidLen; // 跳过 DCID（即我方 localCid）
            int scidLen = serverResponse[pos++] & 0xFF;
            if (scidLen > 0 && pos + scidLen <= serverResponse.length) {
                this.remoteCid = new byte[scidLen];
                System.arraycopy(serverResponse, pos, this.remoteCid, 0, scidLen);
            }
        }
        // ── 非 TLS 模式：两端直接进入 ESTABLISHED，握手完成 ────────────
    }

    // ==================== 包构造工具方法 ====================

    /**
     * 在指定流上发送 STREAM 帧。
     * @param streamId 流 ID（见类说明中的流 ID 编码规则）
     * @param offset 字节偏移（第一片通常为 0）
     * @param data 载荷内容
     * @param fin 是否设置 FIN 位（表示流的最后一帧）
     * @throws IOException UDP 发送异常
     */
    public void sendStreamFrame(long streamId, long offset, byte[] data, boolean fin) throws IOException {
        sendFrame(buildStreamFrame(streamId, offset, data, fin));
    }

    /**
     * 发送 PING 帧（RFC 9000 §19.2）。
     * 对端收到后会产生 ACK 以维持探活或 RTT 测量。
     * @throws IOException UDP 发送异常
     */
    public void sendPing() throws IOException {
        sendFrame(encodeVarInt(0x01)); // PING = 0x01
    }

    // ==================== VarInt 编解码（RFC 9000 附录 A）====================

    /**
     * 发送 CONNECTION_CLOSE 帧（RFC 9000 §19.19）。
     * 通常在测试结束或注入异常时使用。
     * @param errorCode QUIC 错误码，参见 {@code ERR_*} 常量
     * @param triggerFrameType 触发错误的帧类型（未知时填 0）
     * @param reason 可读原因（可传 {@code null} 或空串）
     * @throws IOException UDP 发送异常
     */
    public void sendConnectionClose(long errorCode, long triggerFrameType, String reason) throws IOException {
        sendFrame(buildConnectionCloseFrame(errorCode, triggerFrameType, reason == null ? "" : reason));
    }

    /**
     * 发送已序列化好的任意帧字节——封装为 1-RTT 短头包后发出。
     * <p>
     * 适合注入多帧拼接或非标准帧以触发特定的错误路径：
     * <pre>{@code
     * // 拼接两帧后一次性发送
     * byte[] frames = concat(
     *     QuicSimplyClient.buildStreamFrame(3, 0, data, false),  // 非法：server-uni 让 client 写
     *     QuicSimplyClient.buildPingBytes()
     * );
     * client.sendFrame(frames);
     * }</pre>
     * @param framePayload 完整的 QUIC 帧字节（可包含多帧）
     * @throws IOException UDP 发送异常
     */
    public void sendFrame(byte[] framePayload) throws IOException {
        send(buildRaw1RttPacket(remoteCid, sendPn.getAndIncrement(), framePayload));
    }

    /**
     * 接收一个原始 UDP 数据报，返回其完整字节内容。
     * @param timeoutMs 超时毫秒；超时返回 {@code null}
     * @return 原始包字节，超时返回 {@code null}
     * @throws IOException 套接字 I/O 异常
     */
    public byte[] receiveRaw(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(pkt);
        } catch (SocketTimeoutException e) {
            return null;
        }
        byte[] data = new byte[pkt.getLength()];
        System.arraycopy(buf, 0, data, 0, pkt.getLength());
        return data;
    }

    /**
     * 接收一个 1-RTT 短头包并返回其 payload（帧层字节内容）。
     * 若收到的是长头包（Initial/Handshake），直接返回空数组跳过。
     * @param timeoutMs 超时毫秒；超时返回 {@code null}
     * @return 解析后的帧字节，超时返回 {@code null}，非短头包返回空数组
     * @throws IOException 套接字 I/O 异常
     */
    public byte[] receive1RttPayload(int timeoutMs) throws IOException {
        byte[] raw = receiveRaw(timeoutMs);
        if (raw == null) {
            return null;
        }
        if (isLongHeader(raw)) {
            return new byte[0]; // 长头包，跳过
        }
        // 短头格式：1B firstByte + DEFAULT_CID_LEN B DCID + pnLength B PN + payload
        int pnLength = (raw[0] & 0x03) + 1;
        int payloadStart = 1 + DEFAULT_CID_LEN + pnLength;
        if (payloadStart >= raw.length) {
            return new byte[0];
        }
        int payloadLen = raw.length - payloadStart;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, payloadStart, payload, 0, payloadLen);
        return payload;
    }

    // ==================== 内部工具 ====================

    /** 返回本端 Connection ID（握手时作为 Initial.SCID 发送）。 */
    public byte[] getLocalCid() {
        return localCid;
    }

    /**
     * 返回对端 Connection ID。
     * 握手完成后已更新为服务端的 localCid，用作 1-RTT 短头包的 DCID。
     */
    public byte[] getRemoteCid() {
        return remoteCid;
    }

    /** 返回底层 UDP 套接字，可用于高级操控。 */
    public DatagramSocket getSocket() {
        return socket;
    }

    @Override
    public void close() {
        socket.close();
    }

    private void send(byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, serverAddr));
    }
}
