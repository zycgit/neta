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
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.QueueByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;

/**
 * Mutable per-connection state shared by all HTTP/1.x handlers.
 * <p>
 * This context centralizes the state used by {@link HttpRequestDecoder}, {@link HttpRequestEncoder},
 * {@link HttpResponseDecoder}, {@link HttpResponseEncoder}, {@link HttpRequestAggregator},
 * {@link HttpResponseAggregator}, and {@link HttpServerDuplex}, and is registered in {@link ProtoContext}
 * through {@code context.context(HttpContext.class, impl)}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-28
 */
class HttpContext {
    final RequestDecodeState  req     = new RequestDecodeState();
    final ResponseDecodeState resp    = new ResponseDecodeState();
    final EncodeState         reqEnc  = new EncodeState();
    final EncodeState         respEnc = new EncodeState();
    boolean            transparentMode;
    long               transparentStreamId;
    InboundMessageType inboundErrorType;

    /** Returns the HttpContext for the current connection, creating one if necessary. */
    public static HttpContext getOrCreate(ProtoContext context) {
        HttpContext existing = context.context(HttpContext.class);
        if (existing != null) {
            return existing;
        }

        HttpContext impl = new HttpContext();
        context.context(HttpContext.class, impl);
        return impl;
    }

    /** Returns whether the current connection is in transparent pass-through mode. */
    public boolean isTransparentMode() {
        return this.transparentMode;
    }

    /** Switches transparent pass-through mode and resets the HTTP state for the current connection. */
    public boolean switchTransparentMode(boolean enabled) {
        return this.switchTransparentMode(enabled, 0L);
    }

    /** Switches transparent pass-through mode and remembers the stream identifier associated with the mode switch. */
    public boolean switchTransparentMode(boolean enabled, long streamId) {
        boolean changed = this.transparentMode != enabled;
        this.transparentMode = enabled;
        this.transparentStreamId = streamId;
        this.req.releaseAndReset();
        this.resp.releaseAndReset();
        this.reqEnc.releaseAndReset();
        this.respEnc.releaseAndReset();
        this.inboundErrorType = null;
        return changed;
    }

    /** Returns the stream identifier that should be applied to transparent-mode HTTP objects. */
    public long transparentStreamId() {
        return this.transparentStreamId;
    }

    /** Records the message type associated with the most recent inbound decode error on the current connection. */
    public void markInboundError(InboundMessageType messageType) {
        this.inboundErrorType = messageType;
    }

    /** Returns and clears the message type associated with the most recent inbound decode error. */
    public InboundMessageType consumeInboundErrorType() {
        InboundMessageType messageType = this.inboundErrorType;
        this.inboundErrorType = null;
        return messageType;
    }

    public enum InboundMessageType {
        REQUEST,
        RESPONSE
    }

    public enum DecodePhase {
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

    public static class DecodeState<T extends HttpObject> {
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
        byte[]             headerLineScratch;
        int                headerLineLength;
        HeaderEntryStore   headerEntries;
        private QueueByteBuf           accumulator;
        private ProtoRcvQueue<ByteBuf> accumulatorSource;

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
            this.releaseHeaderLineScratch();
            this.releaseHeaderEntries();
        }

        /**
         * Releases in-flight resources before resetting state on error or abort paths.
         */
        void releaseAndReset() {
            if (this.currentMessage != null) {
                this.currentMessage.release();
            }
            if (this.currentHeaders != null) {
                this.currentHeaders.release();
            }
            this.releaseAccumulator();
            this.releaseHeaderLineScratch();
            this.releaseHeaderEntries();
            this.reset();
        }

        ByteBuf prepareInput(ProtoRcvQueue<ByteBuf> src) {
            if (src == null) {
                throw new NullPointerException("src queue is null.");
            }
            this.finishInput(src);
            if (src.queueSize() <= 1) {
                this.releaseAccumulator();
                ByteBuf first = src.peekMessage();
                return first != null ? first : ByteBuf.EMPTY;
            }
            if (this.accumulator == null || this.accumulatorSource != src || this.accumulator.isFree()) {
                this.releaseAccumulator();
                this.accumulator = ByteBufUtils.queueBuffer(src);
                this.accumulatorSource = src;
            } else {
                this.accumulator.refresh();
            }
            return this.accumulator;
        }

        void finishInput(ProtoRcvQueue<ByteBuf> src) {
            if (this.accumulator != null && !this.accumulator.isFree()) {
                this.accumulator.markReader();
            } else {
                while (src.hasMore()) {
                    ByteBuf first = src.peekMessage();
                    if (first != null && first.readableBytes() > 0) {
                        break;
                    }
                    src.skipMessage(1);
                }
            }
        }

        static void markReaderDeferred(ByteBuf input) {
            if (input instanceof QueueByteBuf) {
                ((QueueByteBuf) input).markReaderDeferred();
            }
            // Borrowed input must not compact storage still referenced by emitted slices.
        }

        void releaseAccumulator() {
            if (this.accumulator == null) {
                this.accumulatorSource = null;
                return;
            }
            if (!this.accumulator.isFree()) {
                this.accumulator.free();
            }
            this.accumulator = null;
            this.accumulatorSource = null;
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
            this.releaseHeaderLineScratch();
            this.releaseHeaderEntries();
        }

        void releaseHeaderEntries() {
            if (this.headerEntries != null) {
                for (DefaultHttpHeaderEntry entry : this.headerEntries) {
                    entry.release();
                }
                this.headerEntries = null;
            }
        }

        void releaseHeaderLineScratch() {
            this.headerLineScratch = null;
            this.headerLineLength = 0;
        }
    }

    public static class RequestDecodeState extends DecodeState<HttpRequest> {
        boolean reqRequestEmitted;

        @Override
        void reset() {
            super.reset();
            this.reqRequestEmitted = false;
        }
    }

    public static class ResponseDecodeState extends DecodeState<HttpResponse> {
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

    public static class EncodeState {
        boolean chunkedEncoding = false;
        boolean trailerStarted  = false;
        final List<ByteBuf> outputs = new ArrayList<>(4);

        List<ByteBuf> prepareOutputs() {
            this.outputs.clear();
            return this.outputs;
        }

        void reset() {
            this.chunkedEncoding = false;
            this.trailerStarted = false;
        }

        void releaseAndReset() {
            ByteBufUtils.releaseAll(this.outputs);
            this.outputs.clear();
            this.reset();
        }
    }

}
