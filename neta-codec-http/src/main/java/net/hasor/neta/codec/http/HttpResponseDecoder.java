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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.QueueByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Decodes inbound socket bytes into staged HTTP/1.x response objects.
 * <p>
 * This decoder is the client-side counterpart of {@link HttpRequestDecoder}. It parses the status
 * line, header fields, and body framing, then emits a staged stream of {@link HttpObject}
 * instances for downstream processing.
 * <p>
 * A single response typically appears downstream as an ordered object stream:
 * <pre>
 * [HttpResponse] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [TrailerHttpHeaders]* -> [LastHttpContent]
 * </pre>
 * Here {@code *} means the segment may appear zero or more times. If the initial header block is
 * complete in one pass, {@link LastHttpHeaders} is emitted directly. If the message has no
 * trailers, that segment is omitted. For close-delimited responses, the final
 * {@link LastHttpContent} is not emitted because message completion is indicated by connection
 * close.
 * <p>
 * Typical usage:
 * <pre>
 * ctx.addLastDecoder("http-resp", new HttpResponseDecoder());
 * ctx.addLast("handler", responseHandler);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 * socket bytes
 * -> HttpResponseDecoder
 * -> HttpResponse + HttpHeaders + HttpContent ...
 * -> business handler
 * </pre>
 * <p>
 * When transparent mode is enabled, the decoder stops interpreting HTTP syntax and forwards the
 * raw payload wrapped as {@link HttpByteBuf}. The same behavior applies to client-side protocol
 * upgrade scenarios.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class HttpResponseDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final Logger logger                          = Logger.getLogger(HttpResponseDecoder.class);
    private static final int    DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int    DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int    DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private final        int    maxInitialLineLength;
    private final        int    maxHeaderSize;
    private final        int    maxChunkSize;

    /**
     * Creates a response decoder with the default limits.
     */
    public HttpResponseDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a response decoder with explicit limits.
     * @param maxInitialLineLength the maximum length of the status line
     * @param maxHeaderSize the maximum total size allowed for all header fields
     * @param maxChunkSize the maximum output size of each content chunk
     */
    public HttpResponseDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        if (maxInitialLineLength <= 0) {
            throw new IllegalArgumentException("maxInitialLineLength must be positive");
        }
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException("maxHeaderSize must be positive");
        }
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("maxChunkSize must be positive");
        }

        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
    }

    /**
     * Initializes the response decoder context.
     */
    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    /**
     * Handles transparent mode switching events.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) {
        if (event.getEventType() != HttpThroughEvent.class) {
            return true;
        }

        HttpThroughEvent modeEvent = (HttpThroughEvent) event.getData();
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        boolean changed = httpCtx.switchTransparentMode(modeEvent.isEnabled(), modeEvent.streamId());
        if (context.getConfig().isPrintLog()) {
            long channelId = context.getChannel().getChannelId();
            logger.info("[HTTP-RESP] channel=" + channelId + " transparent-mode=" + modeEvent.isEnabled() + (changed ? "" : " (unchanged)"));
        }
        return true;
    }

    /**
     * Decodes the inbound byte stream into a sequence of response objects.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        if (httpCtx.isTransparentMode()) {
            while (src.hasMore()) {
                // Backpressure boundary: do not take from src until dst can accept one message.
                if (!dst.hasSlot()) {
                    return ProtoStatus.Stop;
                }

                ByteBuf msg = src.takeMessage();
                if (msg != null) {
                    dst.offerMessage(DefaultHttpByteBuf.newInstance(msg, Math.toIntExact(httpCtx.transparentStreamId())));
                }
            }
            return ProtoStatus.Next;
        }

        boolean printLog = context.getConfig().isPrintLog();
        long channelID = context.getChannel().getChannelId();
        HttpContext.ResponseDecodeState respCtx = httpCtx.resp;
        QueueByteBuf accumulator = null;

        try {
            while (true) {
                switch (respCtx.decoderPhase) {
                    // response
                    case READ_INITIAL: {
                        accumulator = respCtx.prepareAccumulator(src);
                        HttpResponse responseLine = this.decodeStatusLine(accumulator, respCtx);
                        if (responseLine == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, responseLine, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // header
                    case READ_HEADER: {
                        if (accumulator != null) {
                            accumulator.markReader();
                            respCtx.releaseAccumulator();
                            accumulator = null;
                        }

                        HttpHeaders headers = this.decodeHeaders(respCtx, src);
                        if (headers == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, headers, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case DONE_HEADER: {
                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // body
                    case READ_FIXED_LENGTH_CONTENT: {
                        accumulator = respCtx.prepareAccumulator(src);
                        HttpContent content = this.decodeFixedLengthContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_VARIABLE_LENGTH_CONTENT: {
                        accumulator = respCtx.prepareAccumulator(src);
                        // Close-delimited body has no in-band terminator, so we stay in this state
                        // and keep draining bytes until the peer closes the connection.
                        HttpContent content = this.decodeVariableLengthContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);
                        break;
                    }
                    case READ_CHUNK_SIZE: {
                        accumulator = respCtx.prepareAccumulator(src);
                        if (!this.decodeChunkSize(accumulator, respCtx)) {
                            return ProtoStatus.Next;
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_CHUNKED_CONTENT: {
                        accumulator = respCtx.prepareAccumulator(src);
                        HttpContent content = this.decodeChunkedContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_CHUNK_DELIMITER: {
                        accumulator = respCtx.prepareAccumulator(src);
                        if (!this.decodeChunkDelimiter(accumulator, respCtx)) {
                            return ProtoStatus.Next;
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // trailer
                    case READ_HEADER_TRAILER: {
                        if (accumulator != null) {
                            accumulator.markReader();
                            respCtx.releaseAccumulator();
                            accumulator = null;
                        }

                        TrailerHttpHeaders trailers = this.decodeChunkTrailer(respCtx, src);
                        if (trailers == null) {
                            return ProtoStatus.Next;
                        }
                        if (trailers.headerSize() > 0) {
                            this.offerResponseObject(context, dst, respCtx, trailers, channelID, printLog);
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // finish
                    case READ_END: {
                        if (respCtx.emitEmptyEndContent) {
                            this.offerResponseObject(context, dst, respCtx, new DefaultLastHttpContent(ByteBuf.EMPTY), channelID, printLog);
                        }
                        httpCtx.resp.reset();
                        accumulator = respCtx.prepareAccumulator(src);
                        if (accumulator.readableBytes() == 0) {
                            return ProtoStatus.Next;
                        }
                        break;
                    }
                    default:
                        return ProtoStatus.Next;
                }
            }
        } finally {
            respCtx.markAccumulatorReader();
        }
    }

    /**
     * Resets response decoding state when an error path is entered.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        HttpContext httpCtx = context.context(HttpContext.class);
        if (httpCtx != null) {
            httpCtx.markInboundError(HttpContext.InboundMessageType.RESPONSE);
            httpCtx.resp.releaseAndReset();
        }

        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.warn("[HTTP-RESP] channel=" + channelID + " decoder error, response state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        HttpContext httpCtx = context.context(HttpContext.class);
        if (httpCtx != null) {
            httpCtx.resp.releaseAndReset();
        }
    }

    //

    private HttpContext.DecodePhase nextState(HttpContext.ResponseDecodeState respCtx) {
        switch (respCtx.decoderPhase) {
            case READ_INITIAL:
                return respCtx.currentMessage != null ?      //
                        HttpContext.DecodePhase.READ_HEADER ://
                        HttpContext.DecodePhase.READ_INITIAL;
            case READ_HEADER:
                if (respCtx.currentHeaders == null) {
                    return HttpContext.DecodePhase.READ_HEADER;
                }
                return (respCtx.currentHeaders instanceof LastHttpHeaders) ?//
                        HttpContext.DecodePhase.DONE_HEADER :           //
                        HttpContext.DecodePhase.READ_HEADER;
            case DONE_HEADER:
                if (isBodyForbidden(respCtx)) {
                    return endState(respCtx, true);
                }
                if (respCtx.chunked) {
                    return HttpContext.DecodePhase.READ_CHUNK_SIZE;
                }
                if (respCtx.contentLength > 0) {
                    respCtx.bytesRead = 0;
                    return HttpContext.DecodePhase.READ_FIXED_LENGTH_CONTENT;
                }
                if (respCtx.contentLength == 0) {
                    return endState(respCtx, true);
                }
                return respCtx.connectionClose ?                           //
                        HttpContext.DecodePhase.READ_VARIABLE_LENGTH_CONTENT ://
                        endState(respCtx, true);
            case READ_FIXED_LENGTH_CONTENT:
                return respCtx.bytesRead < respCtx.contentLength ?       //
                        HttpContext.DecodePhase.READ_FIXED_LENGTH_CONTENT ://
                        endState(respCtx, false);
            case READ_VARIABLE_LENGTH_CONTENT:
                return HttpContext.DecodePhase.READ_VARIABLE_LENGTH_CONTENT;
            case READ_CHUNK_SIZE:
                if (!respCtx.chunkSizeReady) {
                    return HttpContext.DecodePhase.READ_CHUNK_SIZE;
                }
                respCtx.chunkSizeReady = false;
                return respCtx.currentChunkSize == 0 ?                 //
                        HttpContext.DecodePhase.READ_HEADER_TRAILER : //
                        HttpContext.DecodePhase.READ_CHUNKED_CONTENT;
            case READ_CHUNKED_CONTENT:
                return respCtx.bytesRead < respCtx.currentChunkSize ?   //
                        HttpContext.DecodePhase.READ_CHUNKED_CONTENT :  //
                        HttpContext.DecodePhase.READ_CHUNK_DELIMITER;
            case READ_CHUNK_DELIMITER:
                if (!respCtx.chunkDelimiterReady) {
                    return HttpContext.DecodePhase.READ_CHUNK_DELIMITER;
                }
                respCtx.chunkDelimiterReady = false;
                return HttpContext.DecodePhase.READ_CHUNK_SIZE;
            case READ_HEADER_TRAILER:
                return respCtx.trailerComplete ?                    //
                        endState(respCtx, true) :                  //
                        HttpContext.DecodePhase.READ_HEADER_TRAILER;
            case READ_END:
            default:
                return HttpContext.DecodePhase.READ_END;
        }
    }

    private HttpContext.DecodePhase endState(HttpContext.ResponseDecodeState respCtx, boolean emitEmptyEndContent) {
        respCtx.emitEmptyEndContent = emitEmptyEndContent;
        return HttpContext.DecodePhase.READ_END;
    }

    // status-line
    private HttpResponse decodeStatusLine(QueueByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        while (true) {
            int lineFeedIndex = accumulator.expect((byte) '\n', this.maxInitialLineLength + 2);
            if (lineFeedIndex < 0) {
                return null;
            }

            boolean hasCarriageReturn = lineFeedIndex > 0 && accumulator.getUInt8(lineFeedIndex - 1) == '\r';
            int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
            int consumedBytes = lineLength + (hasCarriageReturn ? 2 : 1);
            if (lineLength == 0) {
                accumulator.skipReadableBytes(consumedBytes);
                accumulator.markReaderDeferred();
                continue;
            }

            if (lineLength > this.maxInitialLineLength) {
                consumeRejectedInitialLine(accumulator, consumedBytes);
                throw new HttpInitialLineTooLongException("status line too long: " + lineLength + " > " + maxInitialLineLength, maxInitialLineLength, lineLength);
            }

            int firstSpace = accumulator.expect((byte) ' ', lineLength);
            if (firstSpace <= 0) {
                consumeRejectedInitialLine(accumulator, consumedBytes);
                throw new HttpBadRequestException("invalid status line");
            }

            int statusStart = firstSpace + 1;
            while (statusStart < lineLength && isHorizontalWhitespace(accumulator.getUInt8(statusStart))) {
                statusStart++;
            }

            int secondSpace = -1;
            for (int i = statusStart; i < lineLength; i++) {
                if (accumulator.getUInt8(i) == ' ') {
                    secondSpace = i;
                    break;
                }
            }

            int statusEnd = secondSpace >= 0 ? secondSpace : lineLength;
            while (statusEnd > statusStart && isHorizontalWhitespace(accumulator.getUInt8(statusEnd - 1))) {
                statusEnd--;
            }
            if (statusStart >= statusEnd) {
                consumeRejectedInitialLine(accumulator, consumedBytes);
                throw new HttpBadRequestException("invalid status code");
            }

            int statusCode = 0;
            for (int i = statusStart; i < statusEnd; i++) {
                int value = accumulator.getUInt8(i);
                if (value < '0' || value > '9') {
                    consumeRejectedInitialLine(accumulator, consumedBytes);
                    throw new HttpBadRequestException("invalid status code");
                }
                statusCode = statusCode * 10 + (value - '0');
            }
            if (statusCode < 100 || statusCode > 999) {
                consumeRejectedInitialLine(accumulator, consumedBytes);
                throw new HttpBadRequestException("invalid status code: " + statusCode);
            }

            int reasonStart = secondSpace < 0 ? lineLength : secondSpace + 1;
            int reasonLength = secondSpace < 0 || reasonStart >= lineLength ? 0 : lineLength - reasonStart;
            HttpVersion version = this.resolveHttpVersion(accumulator, 0, firstSpace);
            HttpStatus status = this.resolveHttpStatus(accumulator, statusCode, reasonStart, reasonLength);
            accumulator.skipReadableBytes(consumedBytes);
            accumulator.markReaderDeferred();
            respCtx.currentMessage = new DefaultHttpResponse(version, status);
            return respCtx.currentMessage;
        }
    }

    private static void consumeRejectedInitialLine(QueueByteBuf accumulator, int consumedBytes) {
        accumulator.skipReadableBytes(consumedBytes);
        accumulator.markReaderDeferred();
    }

    // header
    private HttpHeaders decodeHeaders(HttpContext.ResponseDecodeState respCtx, ProtoRcvQueue<ByteBuf> src) {
        HeaderEntryStore headerEntries = null;
        final boolean[] needsContentLength = { respCtx.contentLength < 0 };
        final boolean[] needsConnectionHeader = { !respCtx.connectionClose };
        if (respCtx.currentMessage != null && HttpVersion.HTTP_1_0.equals(respCtx.currentMessage.protocolVersion())) {
            respCtx.connectionClose = true;
            needsConnectionHeader[0] = false;
        }
        final HeaderEntryStore[] headerEntriesRef = { null };
        boolean endOfHeaders = HttpHeaderStreamingScanner.scan(src, respCtx, this.maxHeaderSize, (line, nameStart, nameLength, valueStart, valueLength) -> {
            if (headerEntriesRef[0] == null) {
                headerEntriesRef[0] = new HeaderEntryStore(16);
            }

            boolean isTransferEncoding = HttpCharSequences.equalsIgnoreCase(line, nameStart, nameLength, HttpHeaderNames.TRANSFER_ENCODING);
            boolean isContentLength = HttpCharSequences.equalsIgnoreCase(line, nameStart, nameLength, HttpHeaderNames.CONTENT_LENGTH);
            boolean isConnection = HttpCharSequences.equalsIgnoreCase(line, nameStart, nameLength, HttpHeaderNames.CONNECTION);

            if (!respCtx.chunked && isTransferEncoding && HttpCharSequences.containsIgnoreCase(line, valueStart, valueLength, HttpHeaderValues.CHUNKED)) {
                respCtx.chunked = true;
                respCtx.contentLength = -1;
                needsContentLength[0] = false;
            } else if (needsContentLength[0] && !respCtx.chunked && isContentLength && !HttpCharSequences.isBlank(line, valueStart, valueLength)) {
                try {
                    respCtx.contentLength = HttpCharSequences.parseLong(line, valueStart, valueLength);
                    if (respCtx.contentLength < 0) {
                        throw new HttpContentTooLargeException("negative Content-Length: " + respCtx.contentLength);
                    }
                    needsContentLength[0] = false;
                } catch (NumberFormatException e) {
                    throw new HttpBadRequestException("invalid Content-Length", e);
                }
            }

            if (needsConnectionHeader[0] && isConnection && HttpCharSequences.containsIgnoreCase(line, valueStart, valueLength, HttpHeaderValues.CLOSE)) {
                respCtx.connectionClose = true;
                needsConnectionHeader[0] = false;
            }

            headerEntriesRef[0].add(DefaultHttpHeaderEntry.newOwnedEntry(line, nameStart, nameLength, valueStart, valueLength));
            return valueLength > 0;
        });
        headerEntries = headerEntriesRef[0];

        if (headerEntries == null) {
            respCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : null;
        } else {
            respCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders(headerEntries, false) : new DefaultHttpHeaders(headerEntries, false);
        }

        if (respCtx.currentHeaders != null && respCtx.currentMessage != null) {
            respCtx.currentHeaders.streamId(respCtx.currentMessage.streamId());
        }
        return respCtx.currentHeaders;
    }

    // body
    private HttpContent decodeFixedLengthContent(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        int readable = accumulator.readableBytes();
        long remaining = respCtx.contentLength - respCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.sliceOff(toRead);
            respCtx.bytesRead += toRead;

            remaining = respCtx.contentLength - respCtx.bytesRead;
            if (remaining == 0) {
                return new DefaultLastHttpContent(content);
            } else {
                return new DefaultHttpContent(content);
            }
        }

        return null;
    }

    private HttpContent decodeVariableLengthContent(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        int readable = accumulator.readableBytes();
        if (readable == 0) {
            return null;
        }

        int toRead = Math.min(readable, maxChunkSize);
        ByteBuf content = accumulator.sliceOff(toRead);
        return new DefaultHttpContent(content);
    }

    private boolean decodeChunkSize(QueueByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }

        try {
            respCtx.currentChunkSize = parseChunkSize(line);
            respCtx.bytesRead = 0;
            respCtx.chunkSizeReady = true;
            accumulator.markReaderDeferred();
            return true;
        } finally {
            line.free();
        }
    }

    private HttpContent decodeChunkedContent(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        int readable = accumulator.readableBytes();
        if (readable == 0) {
            return null;
        }

        long remaining = respCtx.currentChunkSize - respCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.sliceOff(toRead);
            respCtx.bytesRead += toRead;
            return new DefaultHttpContent(content);
        }

        return null;
    }

    private boolean decodeChunkDelimiter(QueueByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }

        try {
            if (line.readableBytes() != 0) {
                throw new HttpBadRequestException("invalid chunk delimiter");
            }
            accumulator.markReaderDeferred();
            respCtx.chunkDelimiterReady = true;
            return true;
        } finally {
            line.free();
        }
    }

    private TrailerHttpHeaders decodeChunkTrailer(HttpContext.ResponseDecodeState respCtx, ProtoRcvQueue<ByteBuf> src) {
        if (!respCtx.currentHeadersTrailer) {
            respCtx.currentHeaders = new DefaultTrailerHttpHeaders();
            respCtx.currentHeadersTrailer = true;
        }
        boolean complete = HttpHeaderStreamingScanner.scan(src, respCtx, this.maxHeaderSize, (line, nameStart, nameLength, valueStart, valueLength) -> {
            respCtx.currentHeaders.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(line, nameStart, nameLength, valueStart, valueLength));
            return valueLength > 0;
        });
        if (!complete) {
            return null;
        }

        DefaultTrailerHttpHeaders trailers = (DefaultTrailerHttpHeaders) respCtx.currentHeaders;
        if (respCtx.currentMessage != null) {
            trailers.streamId(respCtx.currentMessage.streamId());
        }
        respCtx.currentHeaders = null;
        respCtx.currentHeadersTrailer = false;
        respCtx.trailerComplete = true;
        return trailers;
    }

    private void offerResponseObject(ProtoContext context, ProtoSndQueue<HttpObject> dst, HttpContext.ResponseDecodeState respCtx, HttpObject httpObject, long channelID, boolean printLog) {
        long packetSequence = ++respCtx.packetSequence;
        if (printLog) {
            logger.info("[HTTP-RESP] channel=" + channelID + " packet=" + packetSequence + " type=" + packetType(httpObject) + " " + packetSummary(respCtx, httpObject));
        }

        if (httpObject instanceof HttpResponse) {
            HttpVersion version = ((HttpResponse) httpObject).protocolVersion();
            context.context(HttpVersion.class, version);
            context.context(HttpScope.class, HttpScope.CONNECTION);
        }
        dst.offerMessage(httpObject);
    }

    // utils
    private static int parseChunkSize(ByteBuf line) {
        int lineLength = line.readableBytes();
        int sizeEnd = line.expect((byte) ';', lineLength);
        if (sizeEnd < 0) {
            sizeEnd = lineLength;
        }

        int sizeStart = 0;
        while (sizeStart < sizeEnd && isHorizontalWhitespace(line.getUInt8(sizeStart))) {
            sizeStart++;
        }
        while (sizeEnd > sizeStart && isHorizontalWhitespace(line.getUInt8(sizeEnd - 1))) {
            sizeEnd--;
        }
        if (sizeStart >= sizeEnd) {
            throw new HttpBadRequestException("empty chunk size");
        }

        long size = 0;
        for (int i = sizeStart; i < sizeEnd; i++) {
            int digit = hexValue(line.getUInt8(i));
            if (digit < 0) {
                throw new HttpBadRequestException("invalid chunk size");
            }
            size = (size << 4) | digit;
            if (size > Integer.MAX_VALUE) {
                throw new HttpBadRequestException("chunk size too large: " + size);
            }
        }
        return (int) size;
    }

    private HttpVersion resolveHttpVersion(ByteBuf line, int offset, int length) {
        if (HttpCharSequences.equalsIgnoreCase(line, offset, length, HttpVersion.HTTP_1_1.text())) {
            return HttpVersion.HTTP_1_1;
        }
        if (HttpCharSequences.equalsIgnoreCase(line, offset, length, HttpVersion.HTTP_1_0.text())) {
            return HttpVersion.HTTP_1_0;
        }
        if (HttpCharSequences.equalsIgnoreCase(line, offset, length, HttpVersion.HTTP_2_0.text())) {
            return HttpVersion.HTTP_2_0;
        }
        if (HttpCharSequences.equalsIgnoreCase(line, offset, length, HttpVersion.HTTP_3_0.text())) {
            return HttpVersion.HTTP_3_0;
        }
        return HttpVersion.valueOf(this.materializeAscii(line, offset, length));
    }

    private HttpStatus resolveHttpStatus(ByteBuf line, int statusCode, int reasonOffset, int reasonLength) {
        if (reasonLength == 0) {
            return HttpStatus.valueOf(statusCode, "");
        }

        HttpStatus known = HttpStatus.valueOf(statusCode);
        if (known.code() == statusCode && matchesAscii(line, reasonOffset, reasonLength, known.reasonPhrase())) {
            return known;
        }
        return HttpStatus.valueOf(statusCode, this.materializeAscii(line, reasonOffset, reasonLength));
    }

    private String materializeAscii(ByteBuf source, int offset, int length) {
        return length == 0 ? "" : source.getString(offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean matchesAscii(ByteBuf source, int offset, int length, CharSequence expected) {
        if (expected == null || length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if ((char) (source.getByte(offset + i) & 0xFF) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBodyForbidden(HttpContext.ResponseDecodeState respCtx) {
        if (respCtx.currentMessage == null) {
            return false;
        }
        int statusCode = respCtx.currentMessage.status().code();
        return statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200);
    }

    private static int hexValue(int value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static boolean isHorizontalWhitespace(int value) {
        return value == ' ' || value == '\t';
    }

    private static String packetType(HttpObject httpObject) {
        if (httpObject instanceof HttpResponse) {
            return "status-line";
        }
        if (httpObject instanceof TrailerHttpHeaders) {
            return "trailer";
        }
        if (httpObject instanceof LastHttpHeaders) {
            return "headers-end";
        }
        if (httpObject instanceof HttpHeaders) {
            return "headers";
        }
        if (httpObject instanceof LastHttpContent) {
            return "last-content";
        }
        if (httpObject instanceof HttpContent) {
            return "content";
        }
        return httpObject.getClass().getSimpleName();
    }

    private static String packetSummary(HttpContext.ResponseDecodeState respCtx, HttpObject httpObject) {
        String responseSummary = responseSummary(respCtx.currentMessage);
        if (httpObject instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) httpObject;
            return "version=" + response.protocolVersion().text() + " status=" + response.statusText() + " reason=" + response.reasonText();
        }
        if (httpObject instanceof TrailerHttpHeaders) {
            TrailerHttpHeaders trailers = (TrailerHttpHeaders) httpObject;
            return responseSummary + " trailerCount=" + trailers.headerSize();
        }
        if (httpObject instanceof HttpHeaders) {
            HttpHeaders headers = (HttpHeaders) httpObject;
            return responseSummary + " headerCount=" + headers.headerSize() + " end=" + (httpObject instanceof LastHttpHeaders);
        }
        if (httpObject instanceof HttpContent) {
            HttpContent content = (HttpContent) httpObject;
            int readableBytes = content.content() == null ? 0 : content.content().readableBytes();
            return responseSummary + " bytes=" + readableBytes + " end=" + (httpObject instanceof LastHttpContent);
        }
        return responseSummary + " object=" + httpObject;
    }

    private static String responseSummary(HttpResponse response) {
        if (response == null) {
            return "response=<none>";
        }
        return "response=" + response.statusText() + ' ' + response.reasonText();
    }
}
