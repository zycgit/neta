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
import net.hasor.neta.channel.ProtoContext;

/**
 * Per-connection mutable state for all HTTP/1.x handlers.
 * <p>
 * Consolidates state used by {@link HttpRequestDecoder}, {@link HttpRequestEncoder},
 * {@link HttpResponseDecoder}, {@link HttpResponseEncoder}, {@link HttpRequestAggregator},
 * {@link HttpResponseAggregator},
 * and {@link HttpServerDuplexe} into a single context object registered on
 * {@link ProtoContext} via {@code context.context(HttpContext.class, impl)}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class HttpContext {
    final RequestDecodeState        req     = new RequestDecodeState();
    final ResponseDecodeState       resp    = new ResponseDecodeState();
    final EncodeState<HttpRequest>  reqEnc  = new EncodeState<>();
    final EncodeState<HttpResponse> respEnc = new EncodeState<>();
    boolean            transparentMode;
    InboundMessageType inboundErrorType;

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

    boolean isTransparentMode() {
        return this.transparentMode;
    }

    boolean switchTransparentMode(boolean enabled) {
        boolean changed = this.transparentMode != enabled;
        this.transparentMode = enabled;
        this.req.releaseAndReset();
        this.resp.releaseAndReset();
        this.reqEnc.reset();
        this.respEnc.reset();
        this.inboundErrorType = null;
        return changed;
    }

    void markInboundError(InboundMessageType messageType) {
        this.inboundErrorType = messageType;
    }

    InboundMessageType consumeInboundErrorType() {
        InboundMessageType messageType = this.inboundErrorType;
        this.inboundErrorType = null;
        return messageType;
    }

    enum InboundMessageType {
        REQUEST,
        RESPONSE
    }

    enum DecodePhase {
        READ_INITIAL,
        READ_HEADER,
        DONE_HEADER,
        READ_FIXED_LENGTH_CONTENT,
        READ_VARIABLE_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_HEADER_TRAILER,
        READ_END
    }

    static class DecodeState<T extends HttpObject> {
        DecodePhase        decoderPhase          = DecodePhase.READ_INITIAL;
        T                  currentMessage;
        DefaultHttpHeaders currentHeaders;
        boolean            currentHeadersTrailer = false;
        int                headerBytes           = 0;
        boolean            chunked               = false;
        long               contentLength         = -1;
        long               bytesRead             = 0;
        int                currentChunkSize      = 0;
        boolean            chunkSizeReady        = false;
        boolean            chunkDelimiterReady   = false;
        boolean            trailerComplete       = false;
        boolean            emitEmptyEndContent   = false;
        long               packetSequence        = 0;

        void reset() {
            this.decoderPhase = DecodePhase.READ_INITIAL;
            this.currentMessage = null;
            this.currentHeaders = null;
            this.currentHeadersTrailer = false;
            this.headerBytes = 0;
            this.chunked = false;
            this.contentLength = -1;
            this.bytesRead = 0;
            this.currentChunkSize = 0;
            this.chunkSizeReady = false;
            this.chunkDelimiterReady = false;
            this.trailerComplete = false;
            this.emitEmptyEndContent = false;
            this.packetSequence = 0;
        }

        /** Releases any in-flight resources before resetting state, for error/abort paths. */
        void releaseAndReset() {
            if (this.currentMessage != null) {
                this.currentMessage.release();
            }
            if (this.currentHeaders != null) {
                this.currentHeaders.release();
            }
            this.reset();
        }

        void initForHeaders() {
            this.currentHeaders = null;
            this.currentHeadersTrailer = false;
            this.headerBytes = 0;
            this.chunked = false;
            this.contentLength = -1;
            this.bytesRead = 0;
            this.currentChunkSize = 0;
            this.chunkSizeReady = false;
            this.chunkDelimiterReady = false;
            this.trailerComplete = false;
            this.emitEmptyEndContent = false;
            this.packetSequence = 0;
            this.decoderPhase = DecodePhase.READ_HEADER;
        }
    }

    static class RequestDecodeState extends DecodeState<HttpRequest> {
        boolean reqRequestEmitted;

        @Override
        void reset() {
            super.reset();
            this.reqRequestEmitted = false;
        }
    }

    static class ResponseDecodeState extends DecodeState<HttpResponse> {
        boolean connectionClose;

        @Override
        void reset() {
            super.reset();
            this.connectionClose = false;
        }

        @Override
        void initForHeaders() {
            super.initForHeaders();
            this.connectionClose = false;
        }
    }

    static class EncodeState<T extends HttpObject> {
        boolean chunkedEncoding = false;
        boolean trailerStarted  = false;

        void reset() {
            this.chunkedEncoding = false;
            this.trailerStarted = false;
        }
    }
}
