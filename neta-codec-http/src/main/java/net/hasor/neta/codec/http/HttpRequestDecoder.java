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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * Decodes inbound socket bytes into staged HTTP/1.x request objects.
 * <p>
 * This decoder implements the HTTP/1.x request parsing state machine. It is usually the first
 * protocol handler on the inbound side of an HTTP server pipeline, producing a staged stream of
 * {@link HttpObject} instances.
 * <p>
 * A single request typically appears downstream as an ordered object stream:
 * <pre>
 *   [HttpRequest] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [TrailerHttpHeaders]* -> [LastHttpContent]
 * </pre>
 * Here {@code *} means the segment may appear zero or more times. If the initial header block is
 * complete in one pass, {@link LastHttpHeaders} is emitted directly. If the message has no
 * trailers, that segment is omitted.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastDecoder("http-req", new HttpRequestDecoder());
 *   ctx.addLast("handler", requestHandler);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   socket bytes
 *      -> HttpRequestDecoder
 *      -> HttpRequest + HttpHeaders + HttpContent ...
 *      -> business handler
 * </pre>
 * <p>
 * When transparent mode is enabled, the decoder stops interpreting HTTP syntax and forwards the
 * raw payload wrapped as {@link HttpByteBuf}. This allows the same HTTP/1.x pipeline to carry
 * upgraded protocols such as WebSocket.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class HttpRequestDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final Logger logger                          = Logger.getLogger(HttpRequestDecoder.class);
    private static final int    DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int    DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int    DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private final        int    maxInitialLineLength;
    private final        int    maxHeaderSize;
    private final        int    maxChunkSize;

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

        HttpThroughEvent modeEvent = (HttpThroughEvent) event.getData();
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        boolean changed = httpCtx.switchTransparentMode(modeEvent.enabled());

        if (context.getConfig().isPrintLog()) {
            long channelId = context.getChannel().getChannelId();
            logger.info("[HTTP-REQ] channel=" + channelId + " transparent-mode=" + modeEvent.enabled() + (changed ? "" : " (unchanged)"));
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
                ByteBuf msg = src.takeMessage();
                if (msg != null) {
                    dst.offerMessage(new DefaultHttpByteBuf(msg));
                }
            }
            return ProtoStatus.Next;
        }

        boolean printLog = context.getConfig().isPrintLog();
        long channelID = context.getChannel().getChannelId();
        HttpContext.RequestDecodeState reqCtx = httpCtx.req;
        ByteBuf accumulator = ByteBufUtils.queueBuffer(src);

        try {
            while (true) {
                switch (reqCtx.decoderPhase) {
                    // request
                    case READ_INITIAL: {
                        HttpRequest requestLine = this.decodeInitialLine(context, reqCtx, accumulator);
                        if (requestLine == null) {
                            return ProtoStatus.Next;
                        }
                        httpCtx.req.initForHeaders();
                        this.offerRequestObject(dst, reqCtx, requestLine, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // header
                    case READ_HEADER: {
                        HttpHeaders headers = this.decodeHeaders(context, reqCtx, accumulator);
                        if (headers == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(dst, reqCtx, headers, channelID, printLog);
                        this.updateRequestTransferMode(headers, reqCtx);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case DONE_HEADER: {
                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // body
                    case READ_FIXED_LENGTH_CONTENT: {
                        HttpContent content = this.decodeFixedLengthContent(reqCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(dst, reqCtx, content, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNK_SIZE: {
                        if (!this.decodeChunkSize(accumulator, reqCtx)) {
                            return ProtoStatus.Next;
                        }

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNKED_CONTENT: {
                        HttpContent content = this.decodeChunkedContent(context, reqCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }
                        this.offerRequestObject(dst, reqCtx, content, channelID, printLog);

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    case READ_CHUNK_DELIMITER: {
                        if (!decodeChunkDelimiter(context, reqCtx, accumulator)) {
                            return ProtoStatus.Next;
                        }

                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // trailer
                    case READ_HEADER_TRAILER: {
                        TrailerHttpHeaders trailers = decodeChunkTrailer(context, reqCtx, accumulator);
                        if (trailers == null) {
                            return ProtoStatus.Next;
                        }

                        if (trailers.headerSize() > 0) {
                            this.offerRequestObject(dst, reqCtx, trailers, channelID, printLog);
                        }
                        reqCtx.decoderPhase = this.nextState(reqCtx);
                        break;
                    }
                    // finish
                    case READ_END: {
                        if (reqCtx.emitEmptyEndContent) {
                            this.offerRequestObject(dst, reqCtx, DefaultLastHttpContent.EMPTY, channelID, printLog);
                        }
                        httpCtx.req.reset();
                        if (accumulator.readableBytes() == 0) {
                            return ProtoStatus.Next;
                        }
                        break;
                    }
                    default:
                        return ProtoStatus.Next;
                }
            }
        } catch (HttpBadRequestException e) {
            if (this.recoverBadRequest(reqCtx, dst, accumulator, channelID, printLog, e)) {
                return ProtoStatus.Next;
            }
            throw e;
        } finally {
            accumulator.markReader();
            accumulator.free();
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

    //

    private HttpContext.DecodePhase nextState(HttpContext.RequestDecodeState reqCtx) {
        switch (reqCtx.decoderPhase) {
            case READ_INITIAL:
                return reqCtx.currentMessage != null ?     //
                        HttpContext.DecodePhase.READ_HEADER ://
                        HttpContext.DecodePhase.READ_INITIAL;
            case READ_HEADER:
                if (reqCtx.currentHeaders == null) {
                    return HttpContext.DecodePhase.READ_HEADER;
                }
                return (reqCtx.currentHeaders instanceof LastHttpHeaders) ?//
                        HttpContext.DecodePhase.DONE_HEADER :           //
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
                return reqCtx.bytesRead < reqCtx.contentLength ?         //
                        HttpContext.DecodePhase.READ_FIXED_LENGTH_CONTENT ://
                        endState(reqCtx, false);
            case READ_CHUNK_SIZE:
                if (!reqCtx.chunkSizeReady) {
                    return HttpContext.DecodePhase.READ_CHUNK_SIZE;
                }
                reqCtx.chunkSizeReady = false;
                return reqCtx.currentChunkSize == 0 ?              //
                        HttpContext.DecodePhase.READ_HEADER_TRAILER ://
                        HttpContext.DecodePhase.READ_CHUNKED_CONTENT;
            case READ_CHUNKED_CONTENT:
                return reqCtx.bytesRead < reqCtx.currentChunkSize ? //
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
    private HttpRequest decodeInitialLine(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        ByteBuf line;
        while ((line = accumulator.readLineBuffer(this.maxInitialLineLength + 2)) != null) {
            try {
                if (line.readableBytes() == 0) {
                    accumulator.markReader();
                    continue;
                }

                if (line.readableBytes() > maxInitialLineLength) {
                    String logMessage = "request line too long: " + line.readableBytes() + " > " + maxInitialLineLength;
                    throw new HttpInitialLineTooLongException(logMessage, maxInitialLineLength, line.readableBytes());
                }

                int lineLength = line.readableBytes();
                int firstSpace = line.expect((byte) ' ', lineLength);
                if (firstSpace <= 0) {
                    throw new HttpBadRequestException("invalid request line");
                }

                int secondSpace = -1;
                for (int i = firstSpace + 1; i < lineLength; i++) {
                    if (line.getUInt8(i) == ' ') {
                        secondSpace = i;
                        break;
                    }
                }
                if (secondSpace <= firstSpace + 1 || secondSpace >= lineLength - 1) {
                    throw new HttpBadRequestException("invalid request line");
                }

                String methodText = line.getString(0, firstSpace, StandardCharsets.US_ASCII);
                String uriText = line.getString(firstSpace + 1, secondSpace - firstSpace - 1, StandardCharsets.US_ASCII);
                String versionText = line.getString(secondSpace + 1, lineLength - secondSpace - 1, StandardCharsets.US_ASCII);

                accumulator.markReader();
                reqCtx.currentMessage = new DefaultHttpRequest(versionText, methodText, uriText);
                return reqCtx.currentMessage;
            } finally {
                line.free();
            }
        }

        return null;
    }

    // header
    private HttpHeaders decodeHeaders(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        List<DefaultHttpHeaderEntry> headerEntries = null;
        boolean endOfHeaders = false;
        try {
            ByteBuf line;
            while ((line = accumulator.readLineBuffer(this.maxHeaderSize + 2)) != null) {
                try {
                    int lineLength = line.readableBytes();
                    reqCtx.headerBytes += lineLength + 2; // +2 for CRLF
                    if (reqCtx.headerBytes > this.maxHeaderSize) {
                        throw new HttpHeaderTooLargeException("HTTP headers too large: " + reqCtx.headerBytes + " > " + maxHeaderSize, this.maxHeaderSize, reqCtx.headerBytes);
                    }

                    if (lineLength == 0) {
                        endOfHeaders = true;
                        accumulator.markReader();
                        break;
                    }

                    int colonIdx = line.expect((byte) ':', lineLength);
                    if (colonIdx < 0) {
                        throw new HttpBadRequestException("invalid header line (no colon)");
                    }

                    int nameStart = 0;
                    int nameEnd = colonIdx;
                    while (nameStart < nameEnd && isHorizontalWhitespace(line.getUInt8(nameStart))) {
                        nameStart++;
                    }
                    while (nameEnd > nameStart && isHorizontalWhitespace(line.getUInt8(nameEnd - 1))) {
                        nameEnd--;
                    }

                    int valueStart = colonIdx + 1;
                    while (valueStart < lineLength && isHorizontalWhitespace(line.getUInt8(valueStart))) {
                        valueStart++;
                    }
                    int valueEnd = lineLength;
                    while (valueEnd > valueStart && isHorizontalWhitespace(line.getUInt8(valueEnd - 1))) {
                        valueEnd--;
                    }

                    if (headerEntries == null) {
                        headerEntries = new ArrayList<>();
                    }

                    String name = line.getString(nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
                    String value = line.getString(valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
                    headerEntries.add(new DefaultHttpHeaderEntry(name, value));
                    accumulator.markReader();
                } finally {
                    line.free();
                }
            }
        } catch (RuntimeException e) {
            releaseHeaderEntries(headerEntries);
            throw e;
        }

        if (headerEntries == null) {
            reqCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : null;
        } else {
            reqCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : new DefaultHttpHeaders();
            for (DefaultHttpHeaderEntry entry : headerEntries) {
                reqCtx.currentHeaders.addHeaderEntry(entry);
                entry.release();
            }
        }

        return reqCtx.currentHeaders;
    }

    private void updateRequestTransferMode(HttpHeaders headers, HttpContext.RequestDecodeState reqCtx) {
        if (headers.containsHeader(HttpHeaderNames.TRANSFER_ENCODING)) {
            String te = headers.getString(HttpHeaderNames.TRANSFER_ENCODING);
            if (StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED)) {
                reqCtx.chunked = true;
                reqCtx.contentLength = -1;
            }
        }

        if (!reqCtx.chunked && reqCtx.contentLength < 0 && headers.containsHeader(HttpHeaderNames.CONTENT_LENGTH)) {
            String cl = headers.getString(HttpHeaderNames.CONTENT_LENGTH);
            if (!StringUtils.isBlank(cl)) {
                try {
                    reqCtx.contentLength = Long.parseLong(cl.trim());
                    if (reqCtx.contentLength < 0) {
                        throw new HttpContentTooLargeException("negative Content-Length: " + reqCtx.contentLength);
                    }
                } catch (NumberFormatException e) {
                    throw new HttpBadRequestException("invalid Content-Length: " + cl, e);
                }
            }
        }
    }

    // body
    private HttpContent decodeFixedLengthContent(HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // chunk for fixed-length. (RFC 7230 §3.3.2)
        int readable = accumulator.readableBytes();
        long remaining = reqCtx.contentLength - reqCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), this.maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.sliceOff(toRead);
            reqCtx.bytesRead += toRead;

            remaining = reqCtx.contentLength - reqCtx.bytesRead;
            if (remaining == 0) {
                return new DefaultLastHttpContent(content);// Last chunk
            } else {
                return new DefaultHttpContent(content);
            }
        } else {
            return null;
        }
    }

    private boolean decodeChunkSize(ByteBuf accumulator, HttpContext.RequestDecodeState reqCtx) {
        // chunk for chunk-size. (RFC 7230 §4.1)
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }
        try {
            reqCtx.currentChunkSize = this.parseChunkSize(line);
            reqCtx.bytesRead = 0;
            reqCtx.chunkSizeReady = true;
            accumulator.markReader();
            return true;
        } finally {
            line.free();
        }
    }

    private int parseChunkSize(ByteBuf line) {
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

    private HttpContent decodeChunkedContent(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // chunk for chunk-data. (RFC 7230 §4.1)
        int readable = accumulator.readableBytes();
        long remaining = reqCtx.currentChunkSize - reqCtx.bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = accumulator.sliceOff(toRead);
            reqCtx.bytesRead += toRead;
            return new DefaultHttpContent(content);
        }

        return null;
    }

    private boolean decodeChunkDelimiter(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // Reads the CRLF delimiter after chunk-data.
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }

        try {
            if (line.readableBytes() != 0) {
                throw new HttpBadRequestException("invalid chunk delimiter");
            }
            accumulator.markReader();
            reqCtx.chunkDelimiterReady = true;
            return true;
        } finally {
            line.free();
        }
    }

    private boolean recoverBadRequest(HttpContext.RequestDecodeState reqCtx, ProtoSndQueue<HttpObject> dst, ByteBuf accumulator, long channelID, boolean printLog, HttpBadRequestException e) {
        if (reqCtx.currentMessage == null) {
            return false;
        }
        if (reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNK_SIZE && reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNKED_CONTENT && reqCtx.decoderPhase != HttpContext.DecodePhase.READ_CHUNK_DELIMITER) {
            return false;
        }

        reqCtx.currentMessage.markBad(e.getMessage());

        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
        lastContent.streamId(reqCtx.currentMessage.streamId());
        this.offerRequestObject(dst, reqCtx, lastContent, channelID, printLog);

        accumulator.clear();
        reqCtx.reset();
        return true;
    }

    // trailer
    private TrailerHttpHeaders decodeChunkTrailer(ProtoContext context, HttpContext.RequestDecodeState reqCtx, ByteBuf accumulator) {
        // eads trailing headers after the last chunk (chunk-size = 0). (RFC 7230 §4.1.2)
        if (!reqCtx.currentHeadersTrailer) {
            reqCtx.currentHeaders = new DefaultTrailerHttpHeaders();
            reqCtx.currentHeadersTrailer = true;
        }

        ByteBuf line;
        while ((line = accumulator.readLineBuffer(this.maxHeaderSize + 2)) != null) {
            try {
                int lineLength = line.readableBytes();
                if (lineLength == 0) {
                    DefaultTrailerHttpHeaders trailers = (DefaultTrailerHttpHeaders) reqCtx.currentHeaders;
                    reqCtx.currentHeaders = null;
                    reqCtx.currentHeadersTrailer = false;
                    reqCtx.trailerComplete = true;
                    accumulator.markReader();
                    return trailers;
                }

                int colonIdx = line.expect((byte) ':', lineLength);
                if (colonIdx <= 0) {
                    throw new HttpBadRequestException("invalid trailer line (no colon)");
                }

                int nameStart = 0;
                int nameEnd = colonIdx;
                while (nameStart < nameEnd && isHorizontalWhitespace(line.getUInt8(nameStart))) {
                    nameStart++;
                }
                while (nameEnd > nameStart && isHorizontalWhitespace(line.getUInt8(nameEnd - 1))) {
                    nameEnd--;
                }
                if (nameStart >= nameEnd) {
                    throw new HttpBadRequestException("empty trailer name");
                }

                int valueStart = colonIdx + 1;
                while (valueStart < lineLength && isHorizontalWhitespace(line.getUInt8(valueStart))) {
                    valueStart++;
                }
                int valueEnd = lineLength;
                while (valueEnd > valueStart && isHorizontalWhitespace(line.getUInt8(valueEnd - 1))) {
                    valueEnd--;
                }

                String name = line.getString(nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
                String value = line.getString(valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
                reqCtx.currentHeaders.addHeader(name, value);
                accumulator.markReader();
            } finally {
                line.free();
            }
        }

        return null;
    }

    private void offerRequestObject(ProtoSndQueue<HttpObject> dst, HttpContext.RequestDecodeState reqCtx, HttpObject httpObject, long channelID, boolean printLog) {
        long packetSequence = ++reqCtx.packetSequence;
        if (printLog) {
            logger.info("[HTTP-REQ] channel=" + channelID + " packet=" + packetSequence + " type=" + packetType(httpObject) + " " + packetSummary(reqCtx, httpObject));
        }
        dst.offerMessage(httpObject);
    }

    // utils

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

    private static void releaseHeaderEntries(List<DefaultHttpHeaderEntry> entries) {
        if (entries != null) {
            for (DefaultHttpHeaderEntry entry : entries) {
                entry.release();
            }
        }
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