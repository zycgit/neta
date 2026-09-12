/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.quic.QuicStreamChannel;
import net.hasor.neta.channel.transport.quic.QuicVarInt;
/**
 * HTTP/3 二进制 frame 解码器，将原始字节（{@code ByteBuf}）转换为 {@link Http3Frame} 对象。
 * <p>
 * 该解码器实现了 RFC 9114 定义的 HTTP/3 二进制分帧层。
 * 它会从 QUIC stream 通道或回退元数据中取得 stream ID 与 FIN 标记，解析可变长整数编码的 frame 类型和长度，读取负载，
 * 并为下游的 {@link Http3FrameToHttpDecoder} 输出 {@link Http3Frame} 实例。
 * <p>
 * 对于单向 stream，首个 varint 表示的 stream type 会按 stream 维度缓存，避免在后续数据块上重复读取。
 * <p>
 * <b>解码路径：</b>{@code ByteBuf → Http3Frame → HttpObject}
 * <p>
 * frame 格式（RFC 9114 第 7.1 节）：
 * <pre>
 * HTTP/3 Frame {
 * Type (i), — QUIC variable-length integer
 * Length (i), — QUIC variable-length integer
 * Frame Payload (..),
 * }
 * </pre>
 * @see Http3Frame
 * @see Http3FrameToHttpDecoder
 */
public class Http3FrameDecoder implements ProtoHandler<ByteBuf, Http3Frame> {
    private static final Logger   logger                 = Logger.getLogger(Http3FrameDecoder.class);
    private final boolean         serverMode;
    private final Http3Settings   localSettings;
    /** 非 QUIC 测试场景下使用的回退元数据队列，内容为 streamId + fin。 */
    private final Queue<long[]>   fallbackMeta           = new LinkedList<>();
    /** 记录单向 stream 的类型，避免后续数据块重复读取。 */
    private final Map<Long, Long> uniStreamTypes         = new HashMap<>();
    private long                  nonQuicStreamIdCounter = 0;

    /**
     * 创建一个新的 HTTP/3 二进制 frame 解码器。
     * @param serverMode 当前端点角色标记，供外层组件查询
     */
    public Http3FrameDecoder(boolean serverMode) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode));
    }

    /**
     * 统一的 HTTP/3 settings 初始化入口。
     * <p>
     * 当前 frame 解码层不直接消费 settings 参数，但保留该构造方法以保证整条 H3 codec 链使用统一封装完成初始化。
     */
    public Http3FrameDecoder(boolean serverMode, Http3Settings localSettings) {
        this.serverMode = serverMode;
        this.localSettings = localSettings != null ? new Http3Settings(localSettings) : Http3Settings.defaultLocalSettings(serverMode);
    }

    /**
     * 为下一条入站消息预加载 stream 元数据，供非 QUIC 场景使用。
     * <p>
     * 当没有可用的 {@link QuicStreamChannel} 时，例如在编解码单元测试或 VirtualChannel 环境中，
     * 可以通过该方法补充本应由 QUIC 传输层提供的每条消息元数据。
     * @param streamId QUIC stream ID
     * @param fin 是否为该 stream 的最后一段数据
     */
    public void pushFallbackMeta(long streamId, boolean fin) {
        fallbackMeta.offer(new long[] { streamId, fin ? 1 : 0 });
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Http3Frame> dst) throws Throwable {
        SoChannel<?> ch = context.getChannel();
        QuicStreamChannel streamChannel = (ch instanceof QuicStreamChannel) ? (QuicStreamChannel) ch : null;
        boolean isPrintLog = context.getConfig().isPrintLog();

        while (src.hasMore()) {
            ByteBuf msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            // 提取 stream 元数据。
            long streamId;
            // 空 ByteBuf 表示 QUIC 在重组完成后交付的 FIN 信号。
            boolean fin = msg.readableBytes() == 0;
            if (streamChannel != null) {
                streamId = streamChannel.getStreamId();
            } else {
                long[] meta = fallbackMeta.poll();
                if (meta != null) {
                    streamId = meta[0];
                    fin = meta[1] != 0;
                } else {
                    streamId = nonQuicStreamIdCounter;
                    nonQuicStreamIdCounter += 4;
                    fin = true;
                }
            }

            int dataLen = msg.readableBytes();
            // 空 ByteBuf 表示 QUIC 在重组完成后交付的 FIN 信号。
            if (dataLen == 0) {
                if (fin) {
                    parseFrames(context, dst, streamId, new byte[0], 0, 0, true, isPrintLog);
                }
                continue;
            }
            byte[] data = new byte[dataLen];
            if (dataLen > 0) {
                msg.getBytes(0, data, 0, dataLen);
            }

            // 检查是否为单向 stream，stream ID 的第 1 位为 1 时表示单向。
            if ((streamId & 0x02) != 0) {
                parseUnidirectionalStream(context, dst, streamId, data, 0, dataLen, isPrintLog);
            } else {
                parseFrames(context, dst, streamId, data, 0, dataLen, fin, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * 从原始数据中解析 HTTP/3 frame，并输出 {@link Http3Frame} 对象。
     * <p>
     * 每个 frame 都由 varint type、varint length 和 payload 组成。
     * FIN 标记只会设置到当前数据块中的最后一个 frame 上。
     */
    private void parseFrames(ProtoContext context, ProtoSndQueue<Http3Frame> dst, long streamId, byte[] data, int offset, int length, boolean fin, boolean isPrintLog) {
        int pos = offset;
        int end = offset + length;

        while (pos < end) {
            // 读取 frame 类型，可变长整数编码。
            long[] typeResult = QuicVarInt.decode(data, pos);
            long frameType = typeResult[0];
            pos += (int) typeResult[1];

            // 读取 frame 长度，可变长整数编码。
            long[] lenResult = QuicVarInt.decode(data, pos);
            int frameLength = (int) lenResult[0];
            pos += (int) lenResult[1];

            if (pos + frameLength > end) {
                break; // frame 尚不完整，等待更多数据。
            }

            // 判断这是否为当前数据块中的最后一个 frame。
            boolean lastFrame = (pos + frameLength >= end) && fin;

            // 提取负载。
            byte[] payload = new byte[frameLength];
            if (frameLength > 0) {
                System.arraycopy(data, pos, payload, 0, frameLength);
            }

            dst.offerMessage(new Http3Frame(frameType, streamId, lastFrame, payload));

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV-FRAME] ch=" + channelID + " " + Http3FrameType.name(frameType) + " stream=" + streamId + " fin=" + lastFrame + " len=" + frameLength);
            }

            pos += frameLength;
        }
    }

    /**
     * 解析单向 stream 上的数据。
     * <p>
     * 对于某个 stream 的首个数据块，会先读取并记录 stream type varint。
     * 后续数据块会跳过 stream type，并继续按通用 frame 格式解析负载。
     */
    private void parseUnidirectionalStream(ProtoContext context, ProtoSndQueue<Http3Frame> dst, long streamId, byte[] data, int offset, int length, boolean isPrintLog) {
        if (length == 0) {
            return;
        }

        int pos = offset;
        int end = offset + length;

        // Check if we already know this stream's type
        Long knownType = uniStreamTypes.get(streamId);
        if (knownType == null) {
            // 这是该单向 stream 的首个数据块，需要先读取 stream type varint。
            long[] typeResult = QuicVarInt.decode(data, pos);
            long streamType = typeResult[0];
            pos += (int) typeResult[1];
            uniStreamTypes.put(streamId, streamType);

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV-FRAME] ch=" + channelID + " UNI-STREAM type=" + streamType + " stream=" + streamId);
            }
        }

        // 在该单向 stream 上解析 frame，control stream 也遵循相同的 frame 格式。
        if (pos < end) {
            parseFrames(context, dst, streamId, data, pos, end - pos, false, isPrintLog);
        }
    }

    /**
     * 如果当前为服务端模式，则返回 {@code true}。
     */
    boolean isServerMode() {
        return this.serverMode;
    }

    @Override
    public void onClose(ProtoContext context) {
        fallbackMeta.clear();
        uniStreamTypes.clear();
    }
}
