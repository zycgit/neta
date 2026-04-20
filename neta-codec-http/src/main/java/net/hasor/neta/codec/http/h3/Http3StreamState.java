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
 * 供编解码层跟踪使用的 HTTP/3 stream 状态。
 * <p>
 * HTTP/3 stream 会映射到底层 QUIC stream。每组请求/响应通常对应一个独立的双向 QUIC stream。
 * 该枚举用于记录每个 stream 的 HTTP 层状态。
 * @see Http3Stream
 */
public enum Http3StreamState {
    /** stream 已创建，尚未收发任何 frame。 */
    IDLE,
    /** 已发送或接收 HEADERS frame，正在等待更多数据或 trailers。 */
    OPEN,
    /** 请求/响应交换已经完成，即已收到或发送 FIN。 */
    HALF_CLOSED,
    /** stream 已完全关闭。 */
    CLOSED
}
