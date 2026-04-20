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
import net.hasor.neta.bytebuf.StringView;
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
    private final int           maxInitialLineLength;
    private final int           maxHeaderSize;
    private final int           maxChunkSize;

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
                    dst.offerMessage(new DefaultHttpByteBuf(msg, Math.toIntExact(httpCtx.transparentStreamId())));
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

                        this.offerResponseObject(context, dst, respCtx, responseLine, channelID, printLog);

                        respCtx.decoderPhase = this.nextState(respCtx);
                        break;
                    }
                    // header
                    case READ_HEADER: {
                        HttpHeaders headers = this.decodeHeaders(respCtx, accumulator);
                        if (headers == null) {
                            return ProtoStatus.Next;
                        }

                        this.offerResponseObject(context, dst, respCtx, headers, channelID, printLog);
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

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);

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

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);
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

                        this.offerResponseObject(context, dst, respCtx, content, channelID, printLog);

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

                StringView versionView = StringView.request(line, 0, firstSpace);
                StringView statusView = StringView.request(line, statusStart, statusEnd - statusStart);
                StringView reasonView = secondSpace < 0 || secondSpace + 1 >= lineLength ? null : StringView.request(line, secondSpace + 1, lineLength - secondSpace - 1);
                try {
                    HttpVersion version = HttpVersion.valueOf(versionView);
                    HttpStatus status = HttpStatus.valueOf(statusView, reasonView == null ? "" : reasonView);

                    accumulator.markReader();
                    respCtx.currentMessage = new DefaultHttpResponse(version, status);
                    return respCtx.currentMessage;
                } finally {
                    versionView.release();
                    statusView.release();
                    if (reasonView != null) {
                        reasonView.release();
                    }
                }
            } finally {
                line.free();
            }
        }

        return null;
    }

    // header
    private HttpHeaders decodeHeaders(HttpContext.ResponseDecodeState respCtx, ByteBuf accumulator) {
        List<DefaultHttpHeaderEntry> headerEntries = null;
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
                    headerEntries = new ArrayList<>();
                }

                StringView name = StringView.request(line, nameStart, nameEnd - nameStart);
                StringView value = StringView.request(line, valueStart, valueEnd - valueStart);
                try {
                    headerEntries.add(new DefaultHttpHeaderEntry(name, value));
                    name = null;
                    value = null;
                } finally {
                    if (name != null) {
                        name.release();
                    }
                    if (value != null) {
                        value.release();
                    }
                }
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
            }
        }

        if (respCtx.currentHeaders != null && respCtx.currentMessage != null) {
            respCtx.currentHeaders.streamId(respCtx.currentMessage.streamId());
        }
        return respCtx.currentHeaders;
    }

    private void updateResponseTransferMode(HttpHeaders headers, HttpContext.ResponseDecodeState respCtx) {
        if (headers instanceof DefaultHttpHeaders) {
            if (respCtx.currentMessage != null && HttpVersion.HTTP_1_0.equals(respCtx.currentMessage.protocolVersion())) {
                respCtx.connectionClose = true;
            }

            boolean needsContentLength = respCtx.contentLength < 0;
            boolean needsConnectionHeader = !respCtx.connectionClose;
            for (DefaultHttpHeaderEntry entry : ((DefaultHttpHeaders) headers).headerEntries()) {
                if (!respCtx.chunked && entry.matchesName(HttpHeaderNames.TRANSFER_ENCODING)) {
                    if (HttpCharSequences.containsIgnoreCase(entry.valueText(), HttpHeaderValues.CHUNKED)) {
                        respCtx.chunked = true;
                        respCtx.contentLength = -1;
                        needsContentLength = false;
                    }
                    continue;
                }

                if (needsContentLength && !respCtx.chunked && entry.matchesName(HttpHeaderNames.CONTENT_LENGTH)) {
                    CharSequence cl = entry.valueText();
                    if (!HttpCharSequences.isBlank(cl)) {
                        try {
                            respCtx.contentLength = HttpCharSequences.parseLong(cl);
                            if (respCtx.contentLength < 0) {
                                throw new HttpContentTooLargeException("negative Content-Length: " + respCtx.contentLength);
                            }
                            needsContentLength = false;
                        } catch (NumberFormatException e) {
                            throw new HttpBadRequestException("invalid Content-Length: " + cl, e);
                        }
                    }
                    continue;
                }

                if (needsConnectionHeader && entry.matchesName(HttpHeaderNames.CONNECTION)) {
                    if (HttpCharSequences.containsIgnoreCase(entry.valueText(), HttpHeaderValues.CLOSE)) {
                        respCtx.connectionClose = true;
                        needsConnectionHeader = false;
                    }
                }
            }
            return;
        }

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

                StringView name = StringView.request(line, nameStart, nameEnd - nameStart);
                StringView value = StringView.request(line, valueStart, valueEnd - valueStart);
                try {
                    ((DefaultHttpHeaders) respCtx.currentHeaders).addHeaderEntry(new DefaultHttpHeaderEntry(name, value));
                    name = null;
                    value = null;
                } finally {
                    if (name != null) {
                        name.release();
                    }
                    if (value != null) {
                        value.release();
                    }
                }
                accumulator.markReader();
            } finally {
                line.free();
            }
        }

        return null;
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