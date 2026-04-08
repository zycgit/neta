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
package net.hasor.neta.codec.http.h3;
/**
 * 表示 RFC 9114 第 7.1 节定义的 HTTP/3 frame。
 * <p>
 * 它是二进制线格式（{@code ByteBuf}）与语义 HTTP 对象（{@code HttpObject}）之间的中间表示。
 * <p>
 * 与 HTTP/2 不同，HTTP/3 frame 头中不编码 stream ID，该信息由底层 QUIC 传输提供。
 * 这里将 stream ID 作为每个 frame 的传输元数据携带。
 * <p>
 * frame 格式（RFC 9114 第 7.1 节）：
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),       — QUIC variable-length integer
 *     Length (i),     — QUIC variable-length integer
 *     Frame Payload (..),
 *   }
 * </pre>
 * <p>
 * 解码路径：{@code ByteBuf → Http3Frame → HttpObject}<br>
 * 编码路径：{@code HttpObject → Http3Frame → ByteBuf}
 * @see Http3FrameType
 */
public class Http3Frame {
    private static final byte[] EMPTY = new byte[0];

    private final long    type;
    private final long    streamId;
    private final byte[]  payload;
    private final int     payloadOffset;
    private final int     payloadLength;
    private final boolean fin;

    /**
     * 使用完整参数创建一个 HTTP/3 frame。
     * @param type frame 类型，例如 {@link Http3FrameType#DATA}
     * @param streamId QUIC stream 标识，作为传输元数据
     * @param fin 是否设置 QUIC FIN，表示 stream 结束
     * @param payload frame 负载字节数组
     * @param payloadOffset 负载数组中的起始偏移
     * @param payloadLength 负载字节长度
     */
    public Http3Frame(long type, long streamId, boolean fin, byte[] payload, int payloadOffset, int payloadLength) {
        this.type = type;
        this.streamId = streamId;
        this.fin = fin;
        this.payload = payload != null ? payload : EMPTY;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    /**
     * 使用完整负载创建一个 HTTP/3 frame。
     * @param type frame 类型
     * @param streamId stream ID
     * @param fin 是否结束 stream
     * @param payload 完整负载
     */
    public Http3Frame(long type, long streamId, boolean fin, byte[] payload) {
        this(type, streamId, fin, payload, 0, payload != null ? payload.length : 0);
    }

    /**
     * 创建一个无负载的 HTTP/3 frame。
     * @param type frame 类型
     * @param streamId stream ID
     * @param fin 是否结束 stream
     */
    public Http3Frame(long type, long streamId, boolean fin) {
        this(type, streamId, fin, EMPTY, 0, 0);
    }

    // ========================= 工厂方法 =========================

    /**
     * 创建一个 DATA frame。
     */
    public static Http3Frame data(long streamId, boolean fin, byte[] payload, int offset, int length) {
        return new Http3Frame(Http3FrameType.DATA, streamId, fin, payload, offset, length);
    }

    /**
     * 使用完整负载创建一个 DATA frame。
     */
    public static Http3Frame data(long streamId, boolean fin, byte[] payload) {
        return new Http3Frame(Http3FrameType.DATA, streamId, fin, payload);
    }

    /**
     * 创建一个 HEADERS frame。
     */
    public static Http3Frame headers(long streamId, boolean fin, byte[] headerBlock, int offset, int length) {
        return new Http3Frame(Http3FrameType.HEADERS, streamId, fin, headerBlock, offset, length);
    }

    /**
     * 使用完整负载创建一个 HEADERS frame。
     */
    public static Http3Frame headers(long streamId, boolean fin, byte[] headerBlock) {
        return new Http3Frame(Http3FrameType.HEADERS, streamId, fin, headerBlock);
    }

    /**
     * 创建一个 SETTINGS frame，属于连接级 frame，不绑定 stream ID。
     */
    public static Http3Frame settings(byte[] payload) {
        return new Http3Frame(Http3FrameType.SETTINGS, 0, false, payload);
    }

    /**
     * 创建一个 GOAWAY frame，属于连接级 frame，不绑定 stream ID。
     */
    public static Http3Frame goaway(byte[] payload) {
        return new Http3Frame(Http3FrameType.GOAWAY, 0, false, payload);
    }

    // ========================= 访问器 =========================

    /**
     * 返回 frame 类型码，例如 {@link Http3FrameType#HEADERS}。
     */
    public long type() {
        return type;
    }

    /**
     * 返回 QUIC stream 标识，也就是该 frame 的传输元数据。
     */
    public long streamId() {
        return streamId;
    }

    /**
     * 当设置了 QUIC FIN 时返回 {@code true}，表示 stream 结束。
     */
    public boolean fin() {
        return fin;
    }

    /**
     * 返回原始负载字节数组，需要结合 {@link #payloadOffset()} 和 {@link #payloadLength()} 使用。
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * 返回负载数组中的偏移位置。
     */
    public int payloadOffset() {
        return payloadOffset;
    }

    /**
     * 返回负载字节数。
     */
    public int payloadLength() {
        return payloadLength;
    }

    @Override
    public String toString() {
        return "Http3Frame{type=" + Http3FrameType.name(type) + ", stream=" + streamId + ", fin=" + fin + ", len=" + payloadLength + "}";
    }
}
