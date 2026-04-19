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
package net.hasor.nhttp.server.connector;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpVersion;

/**
 * Protocol-aware response writer, abstracting the difference between HTTP/1.1 and HTTP/2.
 *
 * <p>Created by {@link PipelineFactory#createResponseSink} at pipeline-build time and injected into
 * each request's {@code RequestContext}. {@code InternalServletResponse} writes through this
 * interface without needing to know the underlying protocol.</p>
 *
 * <p><b>HTTP/1.1 behaviour</b></p>
 * <ul>
 *   <li>{@code sendHeaders(streaming=true)} adds {@code Transfer-Encoding: chunked}</li>
 *   <li>{@code sendContent(buf, last=true)} sends the terminating zero-length chunk</li>
 * </ul>
 *
 * <p><b>HTTP/2 behaviour</b></p>
 * <ul>
 *   <li>{@code sendHeaders()} sends a HEADERS frame</li>
 *   <li>{@code sendContent(buf, last=true)} sends a DATA frame with END_STREAM flag</li>
 *   <li>No {@code Transfer-Encoding: chunked} is ever added</li>
 * </ul>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ResponseSink {

    /**
     * Sends the response headers.
     *
     * @param statusCode HTTP status code
     * @param headers    response headers (may be modified by this method for protocol framing)
     * @param streaming  if {@code true}, prepare for follow-up {@link #sendContent} calls;
     *                   HTTP/1.1 impl adds {@code Transfer-Encoding: chunked},
     *                   HTTP/2 impl leaves headers as-is
     */
    void sendHeaders(int statusCode, HttpHeaders headers, boolean streaming);

    /**
     * Sends a body data chunk.
     *
     * @param content response body bytes; may be {@code null} or empty when {@code last=true}
     *                to signal end-of-stream without data
     * @param last    if {@code true} this is the final chunk:
     *                HTTP/1.1 sends a zero-length terminating chunk;
     *                HTTP/2 sets the END_STREAM flag on the DATA frame
     */
    void sendContent(ByteBuf content, boolean last);

    /** Flushes pending data to the network layer. */
    void flush();

    /** Returns the HTTP protocol version for this response stream. */
    HttpVersion protocolVersion();
}
