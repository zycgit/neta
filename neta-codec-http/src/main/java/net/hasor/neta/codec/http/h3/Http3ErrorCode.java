/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.h3;
/**
 * RFC 9114 第 8.1 节定义的 HTTP/3 错误码。
 * <p>
 * 这些错误码会用于 QUIC 的 RESET_STREAM、STOP_SENDING 以及类型为 0x1d 的 CONNECTION_CLOSE frame
 * 中，用来表示应用层协议错误。
 */
public final class Http3ErrorCode {
    /** 无错误。连接或 stream 需要关闭且没有具体错误可报告时使用。 */
    public static final long H3_NO_ERROR               = 0x0100;
    /** 对端违反了协议要求，当前条件未命中更具体的错误码范围。 */
    public static final long H3_GENERAL_PROTOCOL_ERROR = 0x0101;
    /** HTTP 协议栈内部发生错误。 */
    public static final long H3_INTERNAL_ERROR         = 0x0102;
    /** 端点检测到对端创建了自己不会接受的 stream。 */
    public static final long H3_STREAM_CREATION_ERROR  = 0x0103;
    /** HTTP/3 连接所必需的 stream 被关闭或重置。 */
    public static final long H3_CLOSED_CRITICAL_STREAM = 0x0104;
    /** 收到了在当前状态或当前 stream 上不允许出现的 frame。 */
    public static final long H3_FRAME_UNEXPECTED       = 0x0105;
    /** 收到了布局不合法或超出大小限制的 frame。 */
    public static final long H3_FRAME_ERROR            = 0x0106;
    /** 端点检测到对端正在制造过高负载。 */
    public static final long H3_EXCESSIVE_LOAD         = 0x0107;
    /** Stream ID 或 Push ID 使用方式错误。 */
    public static final long H3_ID_ERROR               = 0x0108;
    /** 端点检测到 SETTINGS frame 负载中存在错误。 */
    public static final long H3_SETTINGS_ERROR         = 0x0109;
    /** 在 control stream 起始位置没有收到 SETTINGS frame。 */
    public static final long H3_MISSING_SETTINGS       = 0x010a;
    /** 服务端在未执行任何应用处理的情况下拒绝了请求。 */
    public static final long H3_REQUEST_REJECTED       = 0x010b;
    /** 请求或其响应（包括 pushed response）已被取消。 */
    public static final long H3_REQUEST_CANCELLED      = 0x010c;
    /** 客户端 stream 在未形成完整请求的情况下终止。 */
    public static final long H3_REQUEST_INCOMPLETE     = 0x010d;
    /** HTTP 消息格式错误，无法处理。 */
    public static final long H3_MESSAGE_ERROR          = 0x010e;
    /** 针对 CONNECT 请求建立的 TCP 连接被重置或异常关闭。 */
    public static final long H3_CONNECT_ERROR          = 0x010f;
    /** 请求的操作无法通过 HTTP/3 提供。 */
    public static final long H3_VERSION_FALLBACK       = 0x0110;

    // QPACK 错误码，见 RFC 9204 第 6 节。
    /** QPACK 解压失败。 */
    public static final long QPACK_DECOMPRESSION_FAILED = 0x0200;
    /** QPACK 编码器 stream 错误。 */
    public static final long QPACK_ENCODER_STREAM_ERROR = 0x0201;
    /** QPACK 解码器 stream 错误。 */
    public static final long QPACK_DECODER_STREAM_ERROR = 0x0202;

    private Http3ErrorCode() {
    }

    /**
     * 返回指定错误码的可读名称。
     * @param code 错误码
     * @return 错误码名称
     */
    public static String name(long code) {
        if (code == H3_NO_ERROR)
            return "H3_NO_ERROR";
        if (code == H3_GENERAL_PROTOCOL_ERROR)
            return "H3_GENERAL_PROTOCOL_ERROR";
        if (code == H3_INTERNAL_ERROR)
            return "H3_INTERNAL_ERROR";
        if (code == H3_STREAM_CREATION_ERROR)
            return "H3_STREAM_CREATION_ERROR";
        if (code == H3_CLOSED_CRITICAL_STREAM)
            return "H3_CLOSED_CRITICAL_STREAM";
        if (code == H3_FRAME_UNEXPECTED)
            return "H3_FRAME_UNEXPECTED";
        if (code == H3_FRAME_ERROR)
            return "H3_FRAME_ERROR";
        if (code == H3_EXCESSIVE_LOAD)
            return "H3_EXCESSIVE_LOAD";
        if (code == H3_ID_ERROR)
            return "H3_ID_ERROR";
        if (code == H3_SETTINGS_ERROR)
            return "H3_SETTINGS_ERROR";
        if (code == H3_MISSING_SETTINGS)
            return "H3_MISSING_SETTINGS";
        if (code == H3_REQUEST_REJECTED)
            return "H3_REQUEST_REJECTED";
        if (code == H3_REQUEST_CANCELLED)
            return "H3_REQUEST_CANCELLED";
        if (code == H3_REQUEST_INCOMPLETE)
            return "H3_REQUEST_INCOMPLETE";
        if (code == H3_MESSAGE_ERROR)
            return "H3_MESSAGE_ERROR";
        if (code == H3_CONNECT_ERROR)
            return "H3_CONNECT_ERROR";
        if (code == H3_VERSION_FALLBACK)
            return "H3_VERSION_FALLBACK";
        if (code == QPACK_DECOMPRESSION_FAILED)
            return "QPACK_DECOMPRESSION_FAILED";
        if (code == QPACK_ENCODER_STREAM_ERROR)
            return "QPACK_ENCODER_STREAM_ERROR";
        if (code == QPACK_DECODER_STREAM_ERROR)
            return "QPACK_DECODER_STREAM_ERROR";
        return "UNKNOWN(0x" + Long.toHexString(code) + ")";
    }
}
