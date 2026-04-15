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
 * HTTP/3 连接的协议上下文接口。
 * <p>
 * HTTP/3 运行在 QUIC 之上。该上下文会暴露 HTTP/3 特有状态，例如 QPACK settings 和 stream 信息。
 * 它通过 {@code context.context(Http3Context.class, impl)} 注册到
 * {@link net.hasor.neta.channel.ProtoContext}。
 * </p>
 * <p>
 * 在 {@link net.hasor.neta.channel.ProtoRouting} 中的用法：
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     Http3Context h3 = context.context(Http3Context.class);
 *     if (h3 != null && h3.isReady()) {
 *         return "http3-stream";
 * }
 * return null;
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface Http3Context {
    /**
     * 当已收到远端 SETTINGS frame 时返回 {@code true}。
     */
    boolean isReady();

    /**
     * 如果当前端点是服务端则返回 {@code true}。
     */
    boolean isServer();

    /**
     * 如果当前端点是客户端则返回 {@code true}。
     */
    boolean isClient();

    /**
     * 返回当前仍在跟踪的最大 stream ID。
     */
    long lastStreamId();

    /**
     * 返回远端当前生效的 SETTINGS_MAX_FIELD_SECTION_SIZE 值。
     * 未显式发送时保持 RFC 默认值 {@link Long#MAX_VALUE}。
     */
    long maxFieldSectionSize();

    /**
     * 返回远端当前生效的 QPACK_MAX_TABLE_CAPACITY 值。
     * 未显式发送时保持 RFC 默认值 0。
     */
    long qpackMaxTableCapacity();

    /**
     * 返回远端当前生效的 QPACK_BLOCKED_STREAMS 值。
     * 未显式发送时保持 RFC 默认值 0。
     */
    long qpackBlockedStreams();
}
