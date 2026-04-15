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
 * RFC 9114 第 7.2 节定义的 HTTP/3 frame 类型常量。
 * <p>
 * HTTP/3 frame 在 QUIC stream 上交换。每个 frame 都以类型和长度字段开头，两者都使用 QUIC 可变长整数编码。
 * <p>
 * frame 格式：
 * <pre>
 * HTTP/3 Frame {
 * Type (i),
 * Length (i),
 * Frame Payload (..),
 * }
 * </pre>
 */
public final class Http3FrameType {
    /** DATA frame（0x00），承载任意长度的字节序列。 */
    public static final long DATA         = 0x00;
    /** HEADERS frame（0x01），承载使用 QPACK 编码的 HTTP 字段区段。 */
    public static final long HEADERS      = 0x01;
    /** CANCEL_PUSH frame（0x03），用于请求取消某个服务端 push。 */
    public static final long CANCEL_PUSH  = 0x03;
    /** SETTINGS frame（0x04），用于传递配置参数。 */
    public static final long SETTINGS     = 0x04;
    /** PUSH_PROMISE frame（0x05），承载服务端 push 使用的请求头区段。 */
    public static final long PUSH_PROMISE = 0x05;
    /** GOAWAY frame（0x07），用于发起连接级优雅关闭。 */
    public static final long GOAWAY       = 0x07;
    /** MAX_PUSH_ID frame（0x0d），用于限制服务端可使用的最大 push ID。 */
    public static final long MAX_PUSH_ID  = 0x0d;

    // 保留 frame 类型必须忽略，见 RFC 9114 第 7.2.8 节。
    // 这些值用于 grease：0x1f * N + 0x21，其中 N 为任意非负整数。

    private Http3FrameType() {
    }

    /**
     * 判断 frame 类型是否属于 greasing 保留值，命中后必须忽略。
     * 保留值规则为：0x1f * N + 0x21。
     * @param type frame 类型
     * @return 命中时返回 {@code true}
     */
    public static boolean isReserved(long type) {
        return type >= 0x21 && ((type - 0x21) % 0x1f) == 0;
    }

    /**
     * 返回指定 frame 类型的可读名称。
     * @param type frame 类型
     * @return 类型名称
     */
    public static String name(long type) {
        if (type == DATA)
            return "DATA";
        if (type == HEADERS)
            return "HEADERS";
        if (type == CANCEL_PUSH)
            return "CANCEL_PUSH";
        if (type == SETTINGS)
            return "SETTINGS";
        if (type == PUSH_PROMISE)
            return "PUSH_PROMISE";
        if (type == GOAWAY)
            return "GOAWAY";
        if (type == MAX_PUSH_ID)
            return "MAX_PUSH_ID";
        if (isReserved(type))
            return "RESERVED(0x" + Long.toHexString(type) + ")";
        return "UNKNOWN(0x" + Long.toHexString(type) + ")";
    }
}
