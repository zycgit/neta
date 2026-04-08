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
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.quic.QuicStreamChannel;
import net.hasor.neta.channel.quic.QuicVarInt;

/**
 * HTTP/3 二进制 frame 编码器，将 {@link Http3Frame} 对象转换为原始字节（{@code ByteBuf}）。
 * <p>
 * 该编码器会按照 HTTP/3 线格式序列化每个 {@link Http3Frame}：varint type + varint length + payload。
 * 当写出的 frame 带有 {@code fin=true} 且当前通道是 {@link QuicStreamChannel} 时，它会通过关闭当前 stream channel
 * 传播 QUIC FIN 信号。
 * <p>
 * 对于非 QUIC 通道（例如 VrtChannel 或测试环境），所有 frame 会被打包进同一个 ByteBuf，以保证原子投递。
 * <p>
 * <b>编码路径：</b>{@code HttpObject → Http3Frame → ByteBuf}
 * <p>
 * frame 格式（RFC 9114 第 7.1 节）：
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),       — QUIC variable-length integer
 *     Length (i),     — QUIC variable-length integer
 *     Frame Payload (..),
 *   }
 * </pre>
 * @see Http3Frame
 * @see Http3HttpToFrameEncoder
 */
public class Http3FrameEncoder implements ProtoHandler<Http3Frame, ByteBuf> {
    private static final Logger logger    = Logger.getLogger(Http3FrameEncoder.class);
    /** 可复用的 varint 编码缓冲区，避免为每个 frame 单独分配。 */
    private final        byte[] varintBuf = new byte[16];

    @Override
    /**
     * 将待发送的 HTTP/3 frame 编码为 ByteBuf。
     * QUIC 通道按 frame 逐条输出，非 QUIC 通道按批次打包输出。
     */ public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http3Frame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        if (!src.hasMore()) {
            return ProtoStatus.Next;
        }

        boolean isPrintLog = context.getConfig().isPrintLog();
        SoChannel<?> ch = context.getChannel();
        boolean isQuic = (ch instanceof QuicStreamChannel);

        if (isQuic) {
            // QUIC 模式：逐个写出 frame，并通过关闭 channel 传播 FIN。
            while (src.hasMore()) {
                Http3Frame frame = src.takeMessage();
                if (frame == null) {
                    continue;
                }
                writeFrame(context, dst, frame, isPrintLog);
                if (frame.fin()) {
                    ch.close();
                }
            }
        } else {
            // 非 QUIC 模式：把所有 frame 打包到同一个 ByteBuf。
            List<Http3Frame> frames = new ArrayList<>();
            while (src.hasMore()) {
                Http3Frame frame = src.takeMessage();
                if (frame != null) {
                    frames.add(frame);
                }
            }
            if (!frames.isEmpty()) {
                writeBundledFrames(context, dst, frames, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * 将单个 HTTP/3 frame 写入输出。
     * 序列化格式为：varint type + varint length + payload。
     */
    private void writeFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst, Http3Frame frame, boolean isPrintLog) {
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, frame.type());
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, frame.payloadLength());
        int frameHeaderLen = typeLen + lenLen;

        ByteBuf output = context.byteBufAllocator().buffer(frameHeaderLen + frame.payloadLength());
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        if (frame.payloadLength() > 0) {
            output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
        }
        output.markWriter();
        dst.offerMessage(output);

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength());
        }
    }

    /**
     * 为非 QUIC 通道把多个 HTTP/3 frame 打包进同一个 ByteBuf。
     * 这样可以让测试或虚拟通道按一次入站数据投递整组 frame。
     */
    private void writeBundledFrames(ProtoContext context, ProtoSndQueue<ByteBuf> dst, List<Http3Frame> frames, boolean isPrintLog) {
        // 计算总大小。
        int totalSize = 0;
        for (Http3Frame frame : frames) {
            totalSize += QuicVarInt.encodedLength(frame.type()) + QuicVarInt.encodedLength(frame.payloadLength()) + frame.payloadLength();
        }

        ByteBuf output = context.byteBufAllocator().buffer(totalSize);
        for (Http3Frame frame : frames) {
            int typeLen = QuicVarInt.encodeTo(varintBuf, 0, frame.type());
            int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, frame.payloadLength());
            output.writeBytes(varintBuf, 0, typeLen + lenLen);
            if (frame.payloadLength() > 0) {
                output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
            }

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength() + " (bundled)");
            }
        }
        output.markWriter();
        dst.offerMessage(output);
    }

    @Override
    public void onClose(ProtoContext context) {
        // 无额外资源需要清理。
    }
}
