/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Decodes inbound socket bytes into staged HTTP/1.x request objects.
 * <p>
 * This decoder implements the HTTP/1.x request parsing state machine. It is usually the first
 * protocol handler on the inbound side of an HTTP server pipeline, producing a staged stream of
 * {@link HttpObject} instances.
 * <p>
 * A single request typically appears downstream as an ordered object stream:
 * <pre>
 * [HttpRequest] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [TrailerHttpHeaders]* -> [LastHttpContent]
 * </pre>
 * Here {@code *} means the segment may appear zero or more times. If the initial header block is
 * complete in one pass, {@link LastHttpHeaders} is emitted directly. If the message has no
 * trailers, that segment is omitted.
 * <p>
 * Typical usage:
 * <pre>
 * ctx.addLastDecoder("http-req", new HttpRequestDecoder());
 * ctx.addLast("handler", requestHandler);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 * socket bytes
 * -> HttpRequestDecoder
 * -> HttpRequest + HttpHeaders + HttpContent ...
 * -> business handler
 * </pre>
 * <p>
 * When transparent mode is enabled, the decoder stops interpreting HTTP syntax and forwards the
 * raw payload wrapped as {@link HttpByteBuf}. This allows the same HTTP/1.x pipeline to carry
 * upgraded protocols such as WebSocket.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class HttpRequestDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final HttpMethod[] STANDARD_METHODS                = { HttpMethod.GET, HttpMethod.POST, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.PATCH, HttpMethod.TRACE, HttpMethod.CONNECT };
    private static final Logger       logger                          = Logger.getLogger(HttpRequestDecoder.class);
    private static final int          DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int          DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int          DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private final        int          maxInitialLineLength;
    private final        int          maxHeaderSize;
    private final        int          maxChunkSize;

    /**
     * Creates a request decoder with the default limits.
     */
    public HttpRequestDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a request decoder with explicit limits.
     * @param maxInitialLineLength the maximum length of the request line
     * @param maxHeaderSize the maximum total size allowed for all header fields
     * @param maxChunkSize the maximum output size of each content chunk
     */
    public HttpRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
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
     * Initializes the request decoder context.
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

        HttpThroughEvent throughEvent = (HttpThroughEvent) event.getData();
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        boolean changed = httpCtx.switchTransparentMode(throughEvent.isEnabled(), throughEvent.streamId());

        if (context.getConfig().isPrintLog()) {
            long channelId = context.getChannel().getChannelId();
            logger.info("[HTTP-REQ] channel=" + channelId + " transparent-mode=" + throughEvent.isEnabled() + (changed ? "" : " (unchanged)"));
        }
        return true;
    }

    /**
     * Decodes the inbound byte stream into a sequence of request objects.
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
        HttpContext.RequestDecodeState reqCtx = httpCtx.req;
        ByteBuf accumulator = null;

        try {
            while (true) {
                switch (reqCtx.decoderPhase) {
                    // request
                    case READ_INITIAL: {
                        reqCtx.finishInput(src);
                        reqCtx.releaseAccumulator();
                        accumulator = null;
                        HttpRequest requestLine = this.decodeInitialLine(reqCtx, src);
                        if (requestLine == null) {
                            return ProtoStatus.Next;
                        }
                        httpCtx.req.initForHeaders();
                        this.offerRequestObject(context, dst, reqCtx, requestLine, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // header
                    case READ_HEADER: {
                        if (accumulator != null) {
                            reqCtx.finishInput(src);
                            reqCtx.releaseAccumulator();
                            accumulator = null;
                        }

                        HttpHeaders headers = this.decodeHeaders(reqCtx, src);
                        if (headers == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(context, dst, reqCtx, headers, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case DONE_HEADER: {
                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // body
                    case READ_FIXED_LENGTH_CONTENT: {
                        accumulator = reqCtx.prepareInput(src);
                        HttpContent content = this.decodeFixedLengthContent(reqCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(context, dst, reqCtx, content, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNK_SIZE: {
                        accumulator = reqCtx.prepareInput(src);
                        if (!this.decodeChunkSize(accumulator, reqCtx)) {
                            return ProtoStatus.Next;
                        }

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNKED_CONTENT: {
                        accumulator = reqCtx.prepareInput(src);
                        HttpContent content = this.decodeChunkedContent(context, reqCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(context, dst, reqCtx, content, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNK_DELIMITER: {
                        accumulator = reqCtx.prepareInput(src);
                        if (!decodeChunkDelimiter(context, reqCtx, accumulator)) {
                            return ProtoStatus.Next;
                        }

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // trailer
                    case READ_HEADER_TRAILER: {
                        if (accumulator != null) {
                            reqCtx.finishInput(src);
                            reqCtx.releaseAccumulator();
                            accumulator = null;
                        }
                        TrailerHttpHeaders trailers = decodeChunkTrailer(reqCtx, src);
                        if (trailers == null) {
                            return ProtoStatus.Next;
                        }

                        if (trailers.headerSize() > 0) {
                            this.offerRequestObject(context, dst, reqCtx, trailers, channelID, printLog);
                        }
                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // finish
                    case READ_END: {
                        if (reqCtx.emitEmptyEndContent) {
                            this.offerRequestObject(context, dst, reqCtx, new DefaultLastHttpContent(ByteBuf.EMPTY), channelID, printLog);
                        }
                        httpCtx.req.reset();
                        reqCtx.finishInput(src);
                        if (!src.hasMore()) {
                            return ProtoStatus.Next;
                        }
                        break;
                    }
                    default:
                        return ProtoStatus.Next;
                }
            }
        } catch (HttpBadRequestException e) {
            if (this.recoverBadRequest(context, reqCtx, dst, accumulator, channelID, printLog, e)) {
                return ProtoStatus.Next;
            }
            throw e;
        } finally {
            reqCtx.finishInput(src);
        }
    }

    /**
     * Resets request decoding state when an error path is entered.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        HttpContext httpCtx = context.context(HttpContext.class);
        if (httpCtx != null) {
            httpCtx.markInboundError(HttpContext.InboundMessageType.REQUEST);
            httpCtx.req.releaseAndReset();
        }

        long channelID = context.getChannel().getChannelId();
        if (context.getConfig().isPrintLog()) {
            logger.error("[HTTP-REQ] channel=" + channelID + " decoder error, request state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } else {
            logger.error("[HTTP-REQ] channel=" + channelID + " decoder error, request state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        HttpContext httpCtx = context.context(HttpContext.class);
        if (httpCtx != null) {
            httpCtx.req.releaseAndReset();
        }
    }

    //

    private HttpContext.DecodePhase nextState(HttpContext.RequestDecodeState reqCtx) {
        switch (reqCtx.decoderPhase) {
            case READ_INITIAL:
                return reqCtx.currentMessage != null ?       //
                        HttpContext.DecodePhase.READ_HEADER ://
                        HttpContext.DecodePhase.READ_INITIAL;
            case READ_HEADER:
                if (reqCtx.currentHeaders == null) {
                    return HttpContext.DecodePhase.READ_HEADER;
                }
                return (reqCtx.currentHeaders instanceof LastHttpHeaders) ? //
                        HttpContext.DecodePhase.DONE_HEADER :               //
                        HttpContext.DecodePhase.READ_HEADER;
            case DONE_HEADER:
                if (reqCtx.chunked) {
                    return HttpContext.DecodePhase.READ_CHUNK_SIZE;
                }
                if (reqCtx.contentLength > 0) {
                    reqCtx.bytesRead = 0;
                    return HttpContext.DecodePhase.READ_FIXED_LENGTH_CONTENT;
                }
                reqCtx.emitEmptyEndContent = true;
                return HttpContext.DecodePhase.READ_END;
            case READ_FIXED_LENGTH_CONTENT:
                return reqCtx.bytesRead < reqCtx.contentLength ?            //
                        HttpContext.DecodePhase.READ_FIXED_LENGTH_CONTENT : //
                        endState(reqCtx, false);
            case READ_CHUNK_SIZE:
                if (!reqCtx.chunkSizeReady) {
                    return HttpContext.DecodePhase.READ_CHUNK_SIZE;
                }
                reqCtx.chunkSizeReady = false;
                return reqCtx.currentChunkSize == 0 ?                //
                        HttpContext.DecodePhase.READ_HEADER_TRAILER ://
                        HttpContext.DecodePhase.READ_CHUNKED_CONTENT;
            case READ_CHUNKED_CONTENT:
                return reqCtx.bytesRead < reqCtx.currentChunkSize ?   //
                        HttpContext.DecodePhase.READ_CHUNKED_CONTENT ://
                        HttpContext.DecodePhase.READ_CHUNK_DELIMITER;
            case READ_CHUNK_DELIMITER:
                if (!reqCtx.chunkDelimiterReady) {
                    return HttpContext.DecodePhase.READ_CHUNK_DELIMITER;
                }
                reqCtx.chunkDelimiterReady = false;
                return HttpContext.DecodePhase.READ_CHUNK_SIZE;
            case READ_HEADER_TRAILER:
                return reqCtx.trailerComplete ?//
                        endState(reqCtx, true) :   //
                        HttpContext.DecodePhase.READ_HEADER_TRAILER;
            case READ_END:
            default:
                return HttpContext.DecodePhase.READ_END;
        }
    }

    private HttpContext.DecodePhase endState(HttpContext.RequestDecodeState reqCtx, boolean emitEmptyEndContent) {
        reqCtx.emitEmptyEndContent = emitEmptyEndContent;
        return HttpContext.DecodePhase.READ_END;
    }

    // line-part
    private HttpRequest decodeInitialLine(HttpContext.RequestDecodeState reqCtx, ProtoRcvQueue<ByteBuf> src) {
        while (true) {
            ByteBuf accumulator = HttpInitialLineScanner.read(src, reqCtx, this.maxInitialLineLength);
            if (accumulator == null) {
                return null;
            }
            int lineFeedIndex = reqCtx.initialLineFeedIndex;

            boolean hasCarriageReturn = lineFeedIndex > 0 && accumulator.getUInt8(lineFeedIndex - 1) == '\r';
            int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
            int consumedBytes = lineLength + (hasCarriageReturn ? 2 : 1);
            if (lineLength == 0) {
                HttpInitialLineScanner.consume(reqCtx, accumulator, consumedBytes);
                continue;
            }

            if (lineLength > this.maxInitialLineLength) {
                HttpInitialLineScanner.consume(reqCtx, accumulator, consumedBytes);
                throw new HttpInitialLineTooLongException("request line too long: " + lineLength + " > " + maxInitialLineLength, maxInitialLineLength, lineLength);
            }

            int firstSpace = accumulator.expect((byte) ' ', lineLength);
            if (firstSpace <= 0) {
                HttpInitialLineScanner.consume(reqCtx, accumulator, consumedBytes);
                throw new HttpBadRequestException("invalid request line");
            }

            int secondSpace = -1;
            for (int i = firstSpace + 1; i < lineLength; i++) {
                if (accumulator.getUInt8(i) == ' ') {
                    secondSpace = i;
                    break;
                }
            }
            if (secondSpace <= firstSpace + 1 || secondSpace >= lineLength - 1) {
                HttpInitialLineScanner.consume(reqCtx, accumulator, consumedBytes);
                throw new HttpBadRequestException("invalid request line");
            }

            try {
                HttpVersion version = this.resolveHttpVersion(accumulator, secondSpace + 1, lineLength - secondSpace - 1);
                HttpMethod method = resolveStandardMethod(accumulator, firstSpace);
                String uri = this.materializeAscii(accumulator, firstSpace + 1, secondSpace - firstSpace - 1);
                reqCtx.currentMessage = method != null ? new DefaultHttpRequest(version, method, uri) : new DefaultHttpRequest(version.text(), this.materializeAscii(accumulator, 0, firstSpace), uri);
                return reqCtx.currentMessage;
            } finally {
                HttpInitialLineScanner.consume(reqCtx, accumulator, consumedBytes);
            }
        }
    }

    private static HttpMethod resolveStandardMethod(ByteBuf line, int length) {
        for (HttpMethod method : STANDARD_METHODS) {
            String name = method.name();
            if (name.length() != length) {
                continue;
            }
            int index = 0;
            while (index < length && line.getUInt8(index) == name.charAt(index)) {
                index++;
            }
            if (index == length) {
                return method;
            }
        }
        return null;
    }

    // header
    private HttpHeaders decodeHeaders(HttpContext.RequestDecodeState reqCtx, ProtoRcvQueue<ByteBuf> src) {
        boolean endOfHeaders;
        try {
            endOfHeaders = HttpHeaderStreamingScanner.scan(src, reqCtx, this.maxHeaderSize, HttpRequestDecoder::decodeHeaderLine);
        } catch (RuntimeException | Error e) {
            reqCtx.releaseHeaderEntries();
            throw e;
        }
        HeaderEntryStore headerEntries = reqCtx.headerEntries;
        reqCtx.headerEntries = null;

        if (headerEntries == null) {
            reqCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : null;
        } else {
            reqCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders(headerEntries, false) : new DefaultHttpHeaders(headerEntries, false);
        }

        if (reqCtx.currentHeaders != null && reqCtx.currentMessage != null) {
            reqCtx.currentHeaders.streamId(reqCtx.currentMessage.streamId());
        }

        return reqCtx.currentHeaders;
    }

    private static boolean decodeHeaderLine(HttpContext.RequestDecodeState reqCtx, ByteBuf line, int nameStart, int nameLength, int valueStart, int valueLength) {
        if (HttpCharSequences.equalsIgnoreCase(line, nameStart, nameLength, HttpHeaderNames.TRANSFER_ENCODING) && !reqCtx.chunked && HttpCharSequences.containsIgnoreCase(line, valueStart, valueLength, HttpHeaderValues.CHUNKED)) {
            reqCtx.chunked = true;
            reqCtx.contentLength = -1;
        } else if (HttpCharSequences.equalsIgnoreCase(line, nameStart, nameLength, HttpHeaderNames.CONTENT_LENGTH) && reqCtx.contentLength < 0 && !reqCtx.chunked && !HttpCharSequences.isBlank(line, valueStart, valueLength)) {
            try {
                reqCtx.contentLength = HttpCharSequences.parseLong(line, valueStart, valueLength);
                if (reqCtx.contentLength < 0) {
                    throw new HttpContentTooLargeException("negative Content-Length: " + reqCtx.contentLength);
                }
            } catch (NumberFormatException e) {
                throw new HttpBadRequestException("invalid Content-Length", e);
            }
        }

        if (reqCtx.headerEntries == null) {
            reqCtx.headerEntries = new HeaderEntryStore(4);
        }
        reqCtx.headerEntries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, nameStart, nameLength, valueStart, valueLength));
        return true;
    }

    // body
    private HttpContent decodeFixedLengthContent(HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // chunk for fixed-length. (RFC 7230 §3.3.2)
        int readable = accumulator.readableBytes();
        long remaining = reqCtx.contentLength - reqCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), this.maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.slice(0, toRead);
            accumulator.skipReadableBytes(toRead);
            HttpContext.DecodeState.markReaderDeferred(accumulator);
            reqCtx.bytesRead += toRead;

            remaining = reqCtx.contentLength - reqCtx.bytesRead;
            if (remaining == 0) {
                return new DefaultLastHttpContent(content);
            } else {
                return new DefaultHttpContent(content);
            }
        } else {
            return null;
        }
    }

    private boolean decodeChunkSize(ByteBuf accumulator, HttpContext.RequestDecodeState reqCtx) {
        int lineFeedIndex = accumulator.expect((byte) '\n', this.maxHeaderSize + 2);
        if (lineFeedIndex < 0) {
            return false;
        }

        boolean hasCarriageReturn = lineFeedIndex > 0 && accumulator.getUInt8(lineFeedIndex - 1) == '\r';
        int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
        int consumedBytes = lineLength + (hasCarriageReturn ? 2 : 1);
        reqCtx.currentChunkSize = parseChunkSize(accumulator, lineLength);
        reqCtx.bytesRead = 0;
        reqCtx.chunkSizeReady = true;
        accumulator.skipReadableBytes(consumedBytes);
        HttpContext.DecodeState.markReaderDeferred(accumulator);
        return true;
    }

    private static int parseChunkSize(ByteBuf line, int lineLength) {
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

    private HttpContent decodeChunkedContent(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // chunk for chunk-data. (RFC 7230 §4.1)
        int readable = accumulator.readableBytes();
        long remaining = reqCtx.currentChunkSize - reqCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.slice(0, toRead);
            accumulator.skipReadableBytes(toRead);
            HttpContext.DecodeState.markReaderDeferred(accumulator);
            reqCtx.bytesRead += toRead;
            return new DefaultHttpContent(content);
        }

        return null;
    }

    private boolean decodeChunkDelimiter(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        int lineFeedIndex = accumulator.expect((byte) '\n', this.maxHeaderSize + 2);
        if (lineFeedIndex < 0) {
            return false;
        }

        boolean hasCarriageReturn = lineFeedIndex > 0 && accumulator.getUInt8(lineFeedIndex - 1) == '\r';
        int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
        if (lineLength != 0) {
            throw new HttpBadRequestException("invalid chunk delimiter");
        }
        accumulator.skipReadableBytes(hasCarriageReturn ? 2 : 1);
        HttpContext.DecodeState.markReaderDeferred(accumulator);
        reqCtx.chunkDelimiterReady = true;
        return true;
    }

    private boolean recoverBadRequest(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ProtoSndQueue<HttpObject> dst, ByteBuf accumulator, long channelID, boolean printLog, HttpBadRequestException e) {
        if (reqCtx.currentMessage == null) {
            return false;
        }
        if (reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNK_SIZE && reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNKED_CONTENT && reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNK_DELIMITER) {
            return false;
        }

        reqCtx.currentMessage.markBad(e.getMessage());

        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
        lastContent.streamId(reqCtx.currentMessage.streamId());
        this.offerRequestObject(context, dst, reqCtx, lastContent, channelID, printLog);

        accumulator.clear();
        reqCtx.reset();
        return true;
    }

    // trailer
    private TrailerHttpHeaders decodeChunkTrailer(HttpContext.RequestDecodeState reqCtx, ProtoRcvQueue<ByteBuf> src) {
        if (!reqCtx.currentHeadersTrailer) {
            reqCtx.currentHeaders = new DefaultTrailerHttpHeaders();
            reqCtx.currentHeadersTrailer = true;
        }
        boolean complete = HttpHeaderStreamingScanner.scan(src, reqCtx, this.maxHeaderSize, (state, line, nameStart, nameLength, valueStart, valueLength) -> {
            state.currentHeaders.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(line, nameStart, nameLength, valueStart, valueLength));
            return true;
        });
        if (!complete) {
            return null;
        }

        DefaultTrailerHttpHeaders trailers = (DefaultTrailerHttpHeaders) reqCtx.currentHeaders;
        if (reqCtx.currentMessage != null) {
            trailers.streamId(reqCtx.currentMessage.streamId());
        }
        reqCtx.currentHeaders = null;
        reqCtx.currentHeadersTrailer = false;
        reqCtx.trailerComplete = true;
        return trailers;
    }

    private void offerRequestObject(ProtoContext context, ProtoSndQueue<HttpObject> dst, HttpContext.RequestDecodeState reqCtx, HttpObject httpObject, long channelID, boolean printLog) {
        long packetSequence = ++reqCtx.packetSequence;
        if (printLog) {
            logger.info("[HTTP-REQ] channel=" + channelID + " packet=" + packetSequence + " type=" + packetType(httpObject) + " " + packetSummary(reqCtx, httpObject));
        }
        if (httpObject instanceof HttpRequest) {
            HttpVersion version = ((HttpRequest) httpObject).protocolVersion();
            context.context(HttpVersion.class, version);
            context.context(HttpScope.class, HttpScope.CONNECTION);
        }
        dst.offerMessage(httpObject);
    }

    // utils

    private HttpVersion resolveHttpVersion(ByteBuf line, int offset, int length) {
        if (length == 8 && matchesAsciiIgnoreCase(line, offset, "HTTP/1.1")) {
            return HttpVersion.HTTP_1_1;
        }
        if (length == 8 && matchesAsciiIgnoreCase(line, offset, "HTTP/1.0")) {
            return HttpVersion.HTTP_1_0;
        }
        if (length == 8 && matchesAsciiIgnoreCase(line, offset, "HTTP/2.0")) {
            return HttpVersion.HTTP_2_0;
        }
        if (length == 8 && matchesAsciiIgnoreCase(line, offset, "HTTP/3.0")) {
            return HttpVersion.HTTP_3_0;
        }
        return HttpVersion.valueOf(this.materializeAscii(line, offset, length));
    }

    private String materializeAscii(ByteBuf source, int offset, int length) {
        return length == 0 ? "" : source.getString(offset, length, StandardCharsets.US_ASCII);
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

    private static boolean matchesAsciiIgnoreCase(ByteBuf source, int offset, CharSequence expected) {
        for (int i = 0; i < expected.length(); i++) {
            int actual = source.getUInt8(offset + i);
            int target = expected.charAt(i);
            if (actual == target) {
                continue;
            }
            if (actual >= 'a' && actual <= 'z') {
                actual -= 32;
            }
            if (target >= 'a' && target <= 'z') {
                target -= 32;
            }
            if (actual != target) {
                return false;
            }
        }
        return true;
    }

    private static String packetType(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            return "request-line";
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

    private static String packetSummary(HttpContext.RequestDecodeState reqCtx, HttpObject httpObject) {
        String requestSummary = requestSummary(reqCtx.currentMessage);
        if (httpObject instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) httpObject;
            return "method=" + request.method() + " uri=" + request.uri() + " version=" + request.protocolVersion().text();
        }
        if (httpObject instanceof TrailerHttpHeaders) {
            TrailerHttpHeaders trailers = (TrailerHttpHeaders) httpObject;
            return requestSummary + " trailerCount=" + trailers.headerSize();
        }
        if (httpObject instanceof HttpHeaders) {
            HttpHeaders headers = (HttpHeaders) httpObject;
            return requestSummary + " headerCount=" + headers.headerSize() + " end=" + (httpObject instanceof LastHttpHeaders);
        }
        if (httpObject instanceof HttpContent) {
            HttpContent content = (HttpContent) httpObject;
            int readableBytes = content.content() == null ? 0 : content.content().readableBytes();
            return requestSummary + " bytes=" + readableBytes + " end=" + (httpObject instanceof LastHttpContent);
        }
        return requestSummary + " object=" + httpObject;
    }

    private static String requestSummary(HttpRequest request) {
        if (request == null) {
            return "request=<none>";
        }
        return "request=" + request.method() + " " + request.uri();
    }
}
