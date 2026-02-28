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
package net.hasor.neta.codec.http;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.websocket.WebSocketFrameDecoder;
import net.hasor.neta.codec.http.websocket.WebSocketFrameEncoder;

/**
 * Per-connection mutable state for all HTTP/1.x handlers.
 * <p>
 * Consolidates state used by {@link HttpRequestDecoder}, {@link HttpRequestEncoder},
 * {@link HttpResponseDecoder}, {@link HttpResponseEncoder}, {@link HttpObjectAggregator},
 * and {@link HttpServerDuplexe} into a single context object registered on
 * {@link ProtoContext} via {@code context.context(HttpContext.class, impl)}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class HttpContext {

    // ==================== Request Decoder State ====================
    // Used by HttpRequestDecoder

    ReqDecoderState  reqDecoderState     = ReqDecoderState.READ_INITIAL;
    ByteBuf          reqAccumulator;
    // head (request-line + headers)
    HttpRequest      reqCurrentRequest;
    int              reqHeaderBytes      = 0;
    String           reqLastHeaderName;
    HttpHeaders      reqPendingTrailerHeaders;
    // body (content)
    long             reqContentLength    = -1;
    long             reqBytesRead        = 0;
    boolean          reqChunked          = false;
    int              reqCurrentChunkSize = 0;
    RespDecoderState respDecoderState    = RespDecoderState.READ_INITIAL;
    ByteBuf          respAccumulator;

    // ==================== Response Decoder State ====================
    // Used by HttpResponseDecoder
    // head (status-line + headers)
    HttpResponse          respCurrentResponse;
    int                   respHeaderBytes      = 0;
    String                respLastHeaderName;
    HttpHeaders           respPendingTrailerHeaders;
    // body (content)
    long                  respContentLength    = -1;
    long                  respBytesRead        = 0;
    boolean               respChunked          = false;
    int                   respCurrentChunkSize = 0;
    // ==================== Request Encoder State ====================
    // Used by HttpRequestEncoder
    boolean               reqChunkedEncoding   = false;
    // ==================== Response Encoder State ====================
    // Used by HttpResponseEncoder
    boolean               respChunkedEncoding  = false;
    // ==================== Aggregator State ====================
    // Used by HttpObjectAggregator
    HttpMessage           currentMessage;
    HttpHeaders           trailingHeaders;
    ByteBuf               aggregatedContent;
    int                   currentContentLength;
    // ==================== WebSocket State ====================
    // Used by HttpServerDuplexe (connection-level, set after 101 upgrade)
    boolean               upgraded             = false;
    WebSocketFrameDecoder wsDecoder;
    WebSocketFrameEncoder wsEncoder;

    /**
     * Retrieves the existing {@link HttpContext} from the {@link ProtoContext},
     * or creates and registers a new one if none exists.
     */
    static HttpContext getOrCreate(ProtoContext context) {
        HttpContext existing = context.context(HttpContext.class);
        if (existing != null) {
            return existing;
        }
        HttpContext impl = new HttpContext();
        context.context(HttpContext.class, impl);
        return impl;
    }

    void resetReqDecoder() {
        reqDecoderState = ReqDecoderState.READ_INITIAL;
        reqCurrentRequest = null;
        reqHeaderBytes = 0;
        reqLastHeaderName = null;
        reqPendingTrailerHeaders = null;
        reqContentLength = -1;
        reqBytesRead = 0;
        reqChunked = false;
        reqCurrentChunkSize = 0;
    }

    void resetRespDecoder() {
        respDecoderState = RespDecoderState.READ_INITIAL;
        respCurrentResponse = null;
        respHeaderBytes = 0;
        respLastHeaderName = null;
        respPendingTrailerHeaders = null;
        respContentLength = -1;
        respBytesRead = 0;
        respChunked = false;
        respCurrentChunkSize = 0;
    }

    boolean isUpgraded() {
        return upgraded;
    }

    enum ReqDecoderState {
        READ_INITIAL,
        READ_HEADER,
        READ_FIXED_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_TRAILER,
        DONE
    }

    enum RespDecoderState {
        READ_INITIAL,
        READ_HEADER,
        READ_FIXED_LENGTH_CONTENT,
        READ_VARIABLE_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_TRAILER,
        DONE
    }
}
