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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Encodes staged request-side {@link HttpObject} instances into outbound HTTP/1.x bytes.
 * <p>
 * This encoder accepts the same request object stream produced by {@link HttpRequestDecoder} and
 * serializes it back into HTTP/1.x wire format. It is typically used in client pipelines or on
 * the outbound side of a proxy path.
 * <p>
 * A single request usually enters the encoder as an ordered object stream:
 * <pre>
 *   [HttpRequest] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [TrailerHttpHeaders]* -> [LastHttpContent]
 * </pre>
 * Here {@code *} means the segment may appear zero or more times. The initial header section must
 * be closed by {@link LastHttpHeaders}, and aggregated objects are still encoded piece by piece
 * along the same staged dispatch path.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("http-req", new HttpRequestEncoder());
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   HttpRequest + HttpHeaders + HttpContent ...
 *      -> HttpRequestEncoder
 *      -> socket bytes
 * </pre>
 * <p>
 * In transparent mode, the encoder no longer serializes HTTP syntax. Instead, it only accepts
 * {@link HttpByteBuf} and forwards the embedded payload directly. This corresponds to the outbound
 * half of an HTTP/1.x protocol upgrade.
 * <p><b>Ownership:</b> once a request-side {@link HttpObject} is consumed successfully by this
 * encoder, the encoder takes over its lifecycle and releases the source object after producing the
 * encoded output. Callers must not release messages that have already been handed off
 * successfully.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class HttpRequestEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private static final Logger              logger         = Logger.getLogger(HttpRequestEncoder.class);
    private static final byte[]              CRLF           = { '\r', '\n' };
    private static final byte[]              ZERO_CRLF_CRLF = { '0', '\r', '\n', '\r', '\n' };
    private static final int                 SCRATCH_SIZE   = 2048;
    private static final ThreadLocal<byte[]> SCRATCH_BUF    = ThreadLocal.withInitial(() -> new byte[SCRATCH_SIZE]);

    /**
     * Initializes the request encoder context.
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
            logger.info("[HTTP-REQ-ENC] channel=" + channelId + " transparent-mode=" + modeEvent.isEnabled() + (changed ? "" : " (unchanged)"));
        }
        return true;
    }

    /**
     * Encodes request objects into an outbound byte stream.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        HttpContext.EncodeState reqCtx = httpCtx.reqEnc;
        boolean hasAny = false;

        while (src.hasMore()) {
            HttpObject head = src.peekMessage();
            if (head == null) {
                src.takeMessage();
                continue;
            }

            int requiredSlots = this.requiredOutboundSlots(reqCtx, head, httpCtx.isTransparentMode());
            if (!ByteBufUtils.hasWritableSlots(dst, requiredSlots)) {
                return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
            }

            // takeMessage transfers ownership; always release the source message in this call.
            HttpObject msg = src.takeMessage();
            List<ByteBuf> outputs = null;
            try {
                outputs = this.encodeMessage(reqCtx, context, msg, httpCtx.isTransparentMode());
                if (outputs.isEmpty()) {
                    outputs = null;
                    continue;
                }
                dst.offerMessage(outputs);
                outputs = null;
                hasAny = true;
            } finally {
                // Release partially encoded buffers on exceptional paths.
                ByteBufUtils.releaseAll(outputs);
                msg.release();
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        HttpContext existing = context.context(HttpContext.class);
        if (existing != null) {
            existing.reqEnc.releaseAndReset();
        }
    }

    //

    private List<ByteBuf> encodeMessage(HttpContext.EncodeState reqCtx, ProtoContext context, HttpObject msg, boolean transparentMode) {
        List<ByteBuf> outputs = new ArrayList<>(4);

        if (transparentMode) {
            if (!(msg instanceof HttpByteBuf)) {
                throw new HttpProtocolStateException("transparent mode only accepts HttpByteBuf on request encoder.");
            }
            this.appendDirectContent(this.takeOwnedContent((HttpByteBuf) msg), outputs);
            return outputs;
        }

        if (msg instanceof HttpRequest) {
            this.handleRequestLinePart(reqCtx, context, (HttpRequest) msg, outputs);
        }
        if (msg instanceof HttpHeaders) {
            this.handleHeadersPart(reqCtx, context, (HttpHeaders) msg, outputs);
        }
        if (msg instanceof HttpContent) {
            this.handleBodyPart(reqCtx, context, (HttpContent) msg, outputs);
        }
        return outputs;
    }

    private int requiredOutboundSlots(HttpContext.EncodeState reqCtx, HttpObject msg, boolean transparentMode) {
        if (transparentMode) {
            if (!(msg instanceof HttpByteBuf)) {
                throw new HttpProtocolStateException("transparent mode only accepts HttpByteBuf on request encoder.");
            }
            return directContentSlots(((HttpByteBuf) msg).content());
        }

        int requiredSlots = 0;
        boolean chunkedEncoding = reqCtx.chunkedEncoding;
        boolean trailerStarted = reqCtx.trailerStarted;

        if (msg instanceof HttpRequest) {
            requiredSlots++;
            chunkedEncoding = false;
            trailerStarted = false;
        }
        if (msg instanceof HttpHeaders) {
            if (msg instanceof TrailerHttpHeaders) {
                requiredSlots++;
                chunkedEncoding = true;
                if (!trailerStarted) {
                    trailerStarted = true;
                }
            } else {
                requiredSlots++;
                chunkedEncoding = chunkedEncoding || isChunked((HttpHeaders) msg);
            }
        }
        if (msg instanceof HttpContent) {
            ByteBuf body = ((HttpContent) msg).content();
            int bodySlots = chunkedEncoding ? chunkContentSlots(body) : directContentSlots(body);
            if (msg instanceof LastHttpContent) {
                requiredSlots += bodySlots;
                if (trailerStarted || chunkedEncoding) {
                    requiredSlots++;
                }
            } else {
                requiredSlots += bodySlots;
            }
        }

        return requiredSlots;
    }

    // line-part
    private void handleRequestLinePart(HttpContext.EncodeState reqCtx, ProtoContext context, HttpRequest request, List<ByteBuf> outputs) {
        reqCtx.reset();
        ByteBuf buf = context.byteBufAllocator().buffer(128);

        byte[] scratch = SCRATCH_BUF.get();
        int pos = 0;
        byte[] methodBytes = request.method().nameBytes();
        System.arraycopy(methodBytes, 0, scratch, pos, methodBytes.length);
        pos += methodBytes.length;
        scratch[pos++] = ' ';
        String uri = request.uri();
        int uriLen = uri.length();
        byte[] versionBytes = request.protocolVersion().textBytes();
        int totalLen = pos + uriLen + 1 + versionBytes.length + 2;
        if (totalLen <= SCRATCH_SIZE) {
            for (int i = 0; i < uriLen; i++) {
                scratch[pos++] = (byte) uri.charAt(i);
            }
            scratch[pos++] = ' ';
            System.arraycopy(versionBytes, 0, scratch, pos, versionBytes.length);
            pos += versionBytes.length;
            scratch[pos++] = '\r';
            scratch[pos++] = '\n';
            buf.writeBytes(scratch, 0, pos);
        } else {
            buf.writeBytes(scratch, 0, pos);
            writeAscii(buf, uri);
            scratch[0] = ' ';
            System.arraycopy(versionBytes, 0, scratch, 1, versionBytes.length);
            int off = 1 + versionBytes.length;
            scratch[off++] = '\r';
            scratch[off++] = '\n';
            buf.writeBytes(scratch, 0, off);
        }

        buf.markWriter();
        outputs.add(buf);
    }

    // header
    private void handleHeadersPart(HttpContext.EncodeState reqCtx, ProtoContext context, HttpHeaders headers, List<ByteBuf> outputs) {
        if (headers instanceof TrailerHttpHeaders) {
            this.handleTrailerHeadersPart(reqCtx, context, headers, outputs);
            return;
        }
        this.handleInitialHeadersPart(reqCtx, context, headers, outputs);
    }

    private void handleInitialHeadersPart(HttpContext.EncodeState reqCtx, ProtoContext context, HttpHeaders headers, List<ByteBuf> outputs) {
        reqCtx.chunkedEncoding = reqCtx.chunkedEncoding || isChunked(headers);
        boolean closeHeaderSection = headers instanceof LastHttpHeaders;
        ByteBuf buf = context.byteBufAllocator().buffer(256);

        this.writeHeaders(buf, headers);
        if (closeHeaderSection) {
            buf.writeBytes(CRLF, 0, CRLF.length);
        }
        buf.markWriter();

        outputs.add(buf);
    }

    private void handleTrailerHeadersPart(HttpContext.EncodeState reqCtx, ProtoContext context, HttpHeaders headers, List<ByteBuf> outputs) {
        reqCtx.chunkedEncoding = true;
        ByteBuf buf = context.byteBufAllocator().buffer(128);

        if (!reqCtx.trailerStarted) {
            buf.writeByte((byte) '0');
            buf.writeBytes(CRLF, 0, CRLF.length);
            reqCtx.trailerStarted = true;
        }
        this.writeHeaders(buf, headers);
        buf.markWriter();

        outputs.add(buf);
    }

    private void writeHeaders(ByteBuf buf, HttpHeaders headers) {
        if (headers == null || headers.headerNames().isEmpty()) {
            return;
        }
        byte[] scratch = SCRATCH_BUF.get();
        for (String name : headers.headerNames()) {
            for (String value : headers.getValues(name)) {
                int nameLen = name.length();
                int valueLen = value.length();
                int totalLen = nameLen + 2 + valueLen + 2;
                if (totalLen <= SCRATCH_SIZE) {
                    int pos = 0;
                    for (int i = 0; i < nameLen; i++) {
                        scratch[pos++] = (byte) name.charAt(i);
                    }
                    scratch[pos++] = ':';
                    scratch[pos++] = ' ';
                    for (int i = 0; i < valueLen; i++) {
                        scratch[pos++] = (byte) value.charAt(i);
                    }
                    scratch[pos++] = '\r';
                    scratch[pos++] = '\n';
                    buf.writeBytes(scratch, 0, pos);
                } else {
                    writeAscii(buf, name);
                    scratch[0] = ':';
                    scratch[1] = ' ';
                    buf.writeBytes(scratch, 0, 2);
                    writeAscii(buf, value);
                    scratch[0] = '\r';
                    scratch[1] = '\n';
                    buf.writeBytes(scratch, 0, 2);
                }
            }
        }
    }

    // body
    private void handleBodyPart(HttpContext.EncodeState reqCtx, ProtoContext context, HttpContent content, List<ByteBuf> outputs) {
        if (content instanceof LastHttpContent) {
            this.encodeLastContent(reqCtx, context, (LastHttpContent) content, outputs);
        } else {
            this.encodeContent(reqCtx, context, content, outputs);
        }
    }

    private void encodeContent(HttpContext.EncodeState reqCtx, ProtoContext context, HttpContent content, List<ByteBuf> outputs) {
        ByteBuf body = this.takeOwnedContent(content);
        if (reqCtx.chunkedEncoding) {
            this.appendChunkContent(context, body, outputs);
        } else {
            this.appendDirectContent(body, outputs);
        }
    }

    private void encodeLastContent(HttpContext.EncodeState reqCtx, ProtoContext context, LastHttpContent lastContent, List<ByteBuf> outputs) {
        ByteBuf body = this.takeOwnedContent(lastContent);
        if (reqCtx.trailerStarted) {
            if (reqCtx.chunkedEncoding) {
                this.appendChunkContent(context, body, outputs);
            } else {
                this.appendDirectContent(body, outputs);
            }
            this.appendTrailingHeadersTerminator(context, outputs);
        } else if (reqCtx.chunkedEncoding) {
            this.appendChunkContent(context, body, outputs);
            this.appendLastChunk(context, outputs);
        } else {
            this.appendDirectContent(body, outputs);
        }
        reqCtx.reset();
    }

    private void appendChunkContent(ProtoContext context, ByteBuf body, List<ByteBuf> outputs) {
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        ByteBuf prefix = context.byteBufAllocator().buffer(16);
        writeHexInt(prefix, body.readableBytes());
        prefix.writeBytes(CRLF, 0, CRLF.length);
        prefix.markWriter();
        outputs.add(prefix);
        outputs.add(body);

        ByteBuf suffix = context.byteBufAllocator().buffer(CRLF.length);
        suffix.writeBytes(CRLF, 0, CRLF.length);
        suffix.markWriter();

        outputs.add(suffix);
    }

    private void appendDirectContent(ByteBuf body, List<ByteBuf> outputs) {
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        outputs.add(body);
    }

    private void appendLastChunk(ProtoContext context, List<ByteBuf> outputs) {
        ByteBuf lastBuf = context.byteBufAllocator().buffer(ZERO_CRLF_CRLF.length);
        lastBuf.writeBytes(ZERO_CRLF_CRLF, 0, ZERO_CRLF_CRLF.length);
        lastBuf.markWriter();

        outputs.add(lastBuf);
    }

    private void appendTrailingHeadersTerminator(ProtoContext context, List<ByteBuf> outputs) {
        ByteBuf buf = context.byteBufAllocator().buffer(CRLF.length);
        buf.writeBytes(CRLF, 0, CRLF.length);
        buf.markWriter();

        outputs.add(buf);
    }

    private ByteBuf takeOwnedContent(HttpContent content) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return null;// Keep zero-byte ownership in the wrapper so the caller-side finally block releases it.
        }
        return content.transferContent();
    }

    private ByteBuf takeOwnedContent(HttpByteBuf content) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return null;// Keep zero-byte ownership in the wrapper so the caller-side finally block releases it.
        }
        return content.transferContent();
    }

    // utils

    private static void writeAscii(ByteBuf buf, String s) {
        int len = s.length();
        if (len <= SCRATCH_SIZE) {
            byte[] scratch = SCRATCH_BUF.get();
            for (int i = 0; i < len; i++) {
                scratch[i] = (byte) s.charAt(i);
            }
            buf.writeBytes(scratch, 0, len);
        } else {
            buf.writeString(s, StandardCharsets.US_ASCII);
        }
    }

    private static void writeHexInt(ByteBuf buf, int value) {
        byte[] scratch = SCRATCH_BUF.get();
        int idx = 15;
        do {
            int digit = value & 0xF;
            scratch[idx--] = (byte) (digit < 10 ? ('0' + digit) : ('a' + digit - 10));
            value >>>= 4;
        } while (value != 0);
        buf.writeBytes(scratch, idx + 1, 15 - idx);
    }

    private static boolean isChunked(HttpHeaders headers) {
        String value = headers != null ? headers.getString(HttpHeaderNames.TRANSFER_ENCODING) : null;
        return StringUtils.containsIgnoreCase(value, HttpHeaderValues.CHUNKED);
    }

    private static int directContentSlots(ByteBuf body) {
        return body == null || body.readableBytes() == 0 ? 0 : 1;
    }

    private static int chunkContentSlots(ByteBuf body) {
        return body == null || body.readableBytes() == 0 ? 0 : 3;
    }
}