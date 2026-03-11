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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.StringView;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.event.HttpThroughEvent;

/**
 * Decodes raw bytes into HTTP response objects ({@link HttpObject}).
 * <p>
 * This decoder implements the HTTP/1.x response parsing state machine as defined in
 * <a href="https://tools.ietf.org/html/rfc7230">RFC 7230</a>.
 * <p>
 * The decoder emits the following sequence of {@link HttpObject}s for each response:
 * <ol>
 *   <li>{@link DefaultHttpResponse} - the status line</li>
 *   <li>{@link DefaultLastHttpHeaders} - the initial header block</li>
 *   <li>Zero or more {@link DefaultHttpContent} - body chunks</li>
 *   <li>Zero or more {@link DefaultTrailerHttpHeaders} - trailing header blocks for chunked bodies</li>
 *   <li>{@link DefaultLastHttpContent} - marks the end of the response body</li>
 * </ol>
 * <p>
 * Supported transfer modes (RFC 7230 §3.3.3):
 * <ul>
 *   <li>Chunked transfer encoding (§4.1)</li>
 *   <li>Content-Length based body (§3.3.2)</li>
 *   <li>Connection close delimited body (HTTP/1.0 without Content-Length)</li>
 *   <li>No body (1xx, 204, 304 responses)</li>
 * </ul>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("http-response", new HttpResponseDecoder());
 * </pre>
 */
public class HttpResponseDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final Logger logger                          = Logger.getLogger(HttpResponseDecoder.class);
    private static final int    DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int    DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int    DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private final        int    maxInitialLineLength;
    private final        int    maxHeaderSize;
    private final        int    maxChunkSize;

    /** Creates a decoder with default limits. */
    public HttpResponseDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a decoder with the specified limits.
     * @param maxInitialLineLength maximum length of the status line
     * @param maxHeaderSize maximum total size of all headers
     * @param maxChunkSize maximum chunk size for content delivery
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

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
        if (event.getEventType() != HttpThroughEvent.class) {
            return true;
        }

        HttpThroughEvent modeEvent = (HttpThroughEvent) event.getData();
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        boolean changed = httpCtx.switchTransparentMode(modeEvent.enabled());
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelId = context.getChannel().getChannelId();
            logger.info("[HTTP-RESP] channel=" + channelId + " transparent-mode=" + modeEvent.enabled() + (changed ? "" : " (unchanged)"));
        }
        return true;
    }

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
        HttpContext.ResponseDecodeState respCtx = httpCtx.resp;
        ByteBuf accumulator = ByteBufUtils.queueBuffer(src);

        try {
            while (true) {
                switch (respCtx.decoderPhase) {
                    // response
                    case READ_INITIAL: {
                        HttpResponse responseLine = this.decodeStatusLine(accumulator, respCtx);
                        if (responseLine == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(dst, respCtx, responseLine, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // header
                    case READ_HEADER: {
                        HttpHeaders headers = this.decodeHeaders(respCtx, accumulator);
                        if (headers == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(dst, respCtx, headers, channelID, printLog);
                        this.updateResponseTransferMode(headers, respCtx);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case DONE_HEADER: {
                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // body
                    case READ_FIXED_LENGTH_CONTENT: {
                        HttpContent content = this.decodeFixedLengthContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(dst, respCtx, content, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_VARIABLE_LENGTH_CONTENT: {
                        // Close-delimited body has no in-band terminator, so we stay in this state
                        // and keep draining bytes until the peer closes the connection.
                        HttpContent content = this.decodeVariableLengthContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(dst, respCtx, content, channelID, printLog);
                        break;
                    }
                    case READ_CHUNK_SIZE: {
                        if (!this.decodeChunkSize(accumulator, respCtx)) {
                            return ProtoStatus.Next;
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_CHUNKED_CONTENT: {
                        HttpContent content = this.decodeChunkedContent(respCtx, accumulator);
                        if (content == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(dst, respCtx, content, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    case READ_CHUNK_DELIMITER: {
                        if (!this.decodeChunkDelimiter(accumulator, respCtx)) {
                            return ProtoStatus.Next;
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // trailer
                    case READ_HEADER_TRAILER: {
                        TrailerHttpHeaders trailers = this.decodeChunkTrailer(respCtx, accumulator);
                        if (trailers == null) {
                            return ProtoStatus.Next;
                        }
                        if (trailers.headerSize() > 0) {
                            this.offerResponseObject(dst, respCtx, trailers, channelID, printLog);
                        }

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // finish
                    case READ_END: {
                        if (respCtx.emitEmptyEndContent) {
                            this.offerResponseObject(dst, respCtx, DefaultLastHttpContent.EMPTY, channelID, printLog);
                        }
                        httpCtx.resp.reset();
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
            accumulator.markReader();
            accumulator.free();
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        HttpContext httpCtx = context.context(HttpContext.class);
        if (httpCtx != null) {
            httpCtx.resp.reset();
        }

        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.warn("[HTTP-RESP] channel=" + channelID + " decoder error, response state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        return ProtoStatus.Next;
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
    private HttpResponse decodeStatusLine(ByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        ByteBuf line;
        while ((line = accumulator.readLineBuffer(this.maxInitialLineLength + 2)) != null) {
            try {
                if (line.readableBytes() == 0) {
                    accumulator.markReader();
                    continue;
                }

                if (line.readableBytes() > this.maxInitialLineLength) {
                    throw new HttpInitialLineTooLongException("status line too long: " + line.readableBytes() + " > " + maxInitialLineLength, maxInitialLineLength, line.readableBytes());
                }

                int lineLength = line.readableBytes();
                int firstSpace = line.expect((byte) ' ', lineLength);
                if (firstSpace <= 0) {
                    throw new HttpBadRequestException("invalid status line");
                }

                int statusStart = firstSpace + 1;
                while (statusStart < lineLength && isHorizontalWhitespace(line.getUInt8(statusStart))) {
                    statusStart++;
                }

                int secondSpace = -1;
                for (int i = statusStart; i < lineLength; i++) {
                    if (line.getUInt8(i) == ' ') {
                        secondSpace = i;
                        break;
                    }
                }

                int statusEnd = secondSpace >= 0 ? secondSpace : lineLength;
                while (statusEnd > statusStart && isHorizontalWhitespace(line.getUInt8(statusEnd - 1))) {
                    statusEnd--;
                }
                if (statusStart >= statusEnd) {
                    throw new HttpBadRequestException("invalid status code");
                }

                int statusCode = 0;
                for (int i = statusStart; i < statusEnd; i++) {
                    int value = line.getUInt8(i);
                    if (value < '0' || value > '9') {
                        throw new HttpBadRequestException("invalid status code");
                    }
                    statusCode = statusCode * 10 + (value - '0');
                }
                if (statusCode < 100 || statusCode > 999) {
                    throw new HttpBadRequestException("invalid status code: " + statusCode);
                }

                CharSequence versionText = StringView.request(line, 0, firstSpace);
                CharSequence statusText = StringView.request(line, statusStart, statusEnd - statusStart);
                CharSequence reasonText = secondSpace < 0 || secondSpace + 1 >= lineLength ? "" : StringView.request(line, secondSpace + 1, lineLength - secondSpace - 1);

                accumulator.markReader();
                respCtx.currentMessage = new DefaultHttpResponse(versionText, statusText, reasonText);
                return respCtx.currentMessage;
            } finally {
                line.free();
            }
        }

        return null;
    }

    // header
    private HttpHeaders decodeHeaders(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        java.util.List<DefaultHttpHeaderEntry> headerEntries = null;
        boolean endOfHeaders = false;
        ByteBuf line;
        while ((line = accumulator.readLineBuffer(this.maxHeaderSize + 2)) != null) {
            try {
                int lineLength = line.readableBytes();
                respCtx.headerBytes += lineLength + 2;
                if (respCtx.headerBytes > this.maxHeaderSize) {
                    throw new HttpHeaderTooLargeException("HTTP headers too large: " + respCtx.headerBytes + " > " + maxHeaderSize, this.maxHeaderSize, respCtx.headerBytes);
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
                if (nameStart >= nameEnd) {
                    throw new HttpBadRequestException("empty header name");
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
                    headerEntries = new java.util.ArrayList<>();
                }

                String name = line.getString(nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
                CharSequence value = StringView.request(line, valueStart, valueEnd - valueStart);
                headerEntries.add(new DefaultHttpHeaderEntry(name, value));
                accumulator.markReader();
            } finally {
                line.free();
            }
        }

        if (headerEntries == null) {
            respCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : null;
        } else {
            respCtx.currentHeaders = endOfHeaders ? new DefaultLastHttpHeaders() : new DefaultHttpHeaders();
            for (DefaultHttpHeaderEntry entry : headerEntries) {
                respCtx.currentHeaders.addHeaderEntry(entry);
                entry.release();
            }
        }

        if (respCtx.currentHeaders != null && respCtx.currentMessage != null) {
            respCtx.currentHeaders.streamId(respCtx.currentMessage.streamId());
        }
        return respCtx.currentHeaders;
    }

    private void updateResponseTransferMode(HttpHeaders headers, HttpContext.ResponseDecodeState respCtx) {
        if (headers.containsHeader(HttpHeaderNames.TRANSFER_ENCODING)) {
            String te = headers.getString(HttpHeaderNames.TRANSFER_ENCODING);
            if (StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED)) {
                respCtx.chunked = true;
                respCtx.contentLength = -1;
            }
        }

        if (!respCtx.chunked && respCtx.contentLength < 0 && headers.containsHeader(HttpHeaderNames.CONTENT_LENGTH)) {
            String cl = headers.getString(HttpHeaderNames.CONTENT_LENGTH);
            if (StringUtils.isNotBlank(cl)) {
                try {
                    respCtx.contentLength = Long.parseLong(cl.trim());
                    if (respCtx.contentLength < 0) {
                        throw new HttpContentTooLargeException("negative Content-Length: " + respCtx.contentLength);
                    }
                } catch (NumberFormatException e) {
                    throw new HttpBadRequestException("invalid Content-Length: " + cl, e);
                }
            }
        }

        if (headers.containsHeader(HttpHeaderNames.CONNECTION)) {
            String connection = headers.getString(HttpHeaderNames.CONNECTION);
            if (StringUtils.containsIgnoreCase(connection, HttpHeaderValues.CLOSE)) {
                respCtx.connectionClose = true;
            }
        }

        if (respCtx.currentMessage != null && HttpVersion.HTTP_1_0.equals(respCtx.currentMessage.protocolVersion())) {
            respCtx.connectionClose = true;
        }
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

    private boolean decodeChunkSize(ByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }

        try {
            respCtx.currentChunkSize = parseChunkSize(line);
            respCtx.bytesRead = 0;
            respCtx.chunkSizeReady = true;
            accumulator.markReader();
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

    private boolean decodeChunkDelimiter(ByteBuf accumulator, HttpContext.ResponseDecodeState respCtx) {
        ByteBuf line = accumulator.readLineBuffer(this.maxHeaderSize + 2);
        if (line == null) {
            return false;
        }

        try {
            if (line.readableBytes() != 0) {
                throw new HttpBadRequestException("invalid chunk delimiter");
            }
            accumulator.markReader();
            respCtx.chunkDelimiterReady = true;
            return true;
        } finally {
            line.free();
        }
    }

    private TrailerHttpHeaders decodeChunkTrailer(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        if (!respCtx.currentHeadersTrailer) {
            respCtx.currentHeaders = new DefaultTrailerHttpHeaders();
            respCtx.currentHeadersTrailer = true;
        }

        ByteBuf line;
        while ((line = accumulator.readLineBuffer(this.maxHeaderSize + 2)) != null) {
            try {
                int lineLength = line.readableBytes();
                if (lineLength == 0) {
                    DefaultTrailerHttpHeaders trailers = (DefaultTrailerHttpHeaders) respCtx.currentHeaders;
                    if (respCtx.currentMessage != null) {
                        trailers.streamId(respCtx.currentMessage.streamId());
                    }
                    respCtx.currentHeaders = null;
                    respCtx.currentHeadersTrailer = false;
                    respCtx.trailerComplete = true;
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
                CharSequence value = StringView.request(line, valueStart, valueEnd - valueStart);
                respCtx.currentHeaders.addHeader(name, value);
                accumulator.markReader();
            } finally {
                line.free();
            }
        }

        return null;
    }

    private void offerResponseObject(ProtoSndQueue<HttpObject> dst, HttpContext.ResponseDecodeState respCtx, HttpObject httpObject, long channelID, boolean printLog) {
        long packetSequence = ++respCtx.packetSequence;
        if (printLog) {
            logger.info("[HTTP-RESP] channel=" + channelID + " packet=" + packetSequence + " type=" + packetType(httpObject) + " " + packetSummary(respCtx, httpObject));
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