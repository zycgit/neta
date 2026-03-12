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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.event.HttpThroughEvent;

/**
 * Encodes {@link HttpObject} instances into raw bytes for HTTP request messages.
 * <p><b>Ownership:</b> once a request-side {@link HttpObject} is consumed by this
 * encoder, the encoder takes over its lifecycle and releases the source object
 * after the encoded output has been produced. Callers should not release a
 * successfully handed-off message a second time.
 */
public class HttpRequestEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private static final Logger              logger         = Logger.getLogger(HttpRequestEncoder.class);
    private static final byte[]              CRLF           = { '\r', '\n' };
    private static final byte[]              ZERO_CRLF_CRLF = { '0', '\r', '\n', '\r', '\n' };
    private static final int                 SCRATCH_SIZE   = 2048;
    private static final ThreadLocal<byte[]> SCRATCH_BUF    = ThreadLocal.withInitial(() -> new byte[SCRATCH_SIZE]);

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

        if (context.getConfig().isPrintLog()) {
            long channelId = context.getChannel().getChannelId();
            logger.info("[HTTP-REQ-ENC] channel=" + channelId + " transparent-mode=" + modeEvent.enabled() + (changed ? "" : " (unchanged)"));
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        HttpContext httpCtx = HttpContext.getOrCreate(context);
        HttpContext.EncodeState<HttpRequest> reqCtx = httpCtx.reqEnc;
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            boolean consumed = false;
            try {
                if (httpCtx.isTransparentMode()) {
                    consumed = true;
                    if (!(msg instanceof HttpByteBuf)) {
                        throw new HttpProtocolViolationException("transparent mode only accepts HttpByteBuf on request encoder.");
                    }
                    this.offerDirectContent(((HttpByteBuf) msg).content(), dst);
                    continue;
                }

                if (msg instanceof HttpRequest) {
                    consumed = true;
                    this.handleRequestLinePart(reqCtx, context, (HttpRequest) msg, dst);
                }
                if (msg instanceof HttpHeaders) {
                    consumed = true;
                    this.handleHeadersPart(reqCtx, context, (HttpHeaders) msg, dst);
                }
                if (msg instanceof HttpContent) {
                    consumed = true;
                    this.handleBodyPart(reqCtx, context, (HttpContent) msg, dst);
                }
            } finally {
                if (consumed) {
                    msg.release();
                }
            }
        }

        return ProtoStatus.Next;
    }

    //

    // line-part
    private void handleRequestLinePart(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
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
        dst.offerMessage(buf);
    }

    // header
    private void handleHeadersPart(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpHeaders headers, ProtoSndQueue<ByteBuf> dst) {
        if (headers instanceof TrailerHttpHeaders) {
            this.handleTrailerHeadersPart(reqCtx, context, headers, dst);
            return;
        }
        this.handleInitialHeadersPart(reqCtx, context, headers, dst);
    }

    private void handleInitialHeadersPart(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpHeaders headers, ProtoSndQueue<ByteBuf> dst) {
        reqCtx.chunkedEncoding = reqCtx.chunkedEncoding || isChunked(headers);
        boolean closeHeaderSection = headers instanceof LastHttpHeaders;
        ByteBuf buf = context.byteBufAllocator().buffer(256);

        this.writeHeaders(buf, headers);
        if (closeHeaderSection) {
            buf.writeBytes(CRLF, 0, CRLF.length);
        }
        buf.markWriter();

        dst.offerMessage(buf);
    }

    private void handleTrailerHeadersPart(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpHeaders headers, ProtoSndQueue<ByteBuf> dst) {
        reqCtx.chunkedEncoding = true;
        ByteBuf buf = context.byteBufAllocator().buffer(128);

        if (!reqCtx.trailerStarted) {
            buf.writeByte((byte) '0');
            buf.writeBytes(CRLF, 0, CRLF.length);
            reqCtx.trailerStarted = true;
        }
        this.writeHeaders(buf, headers);
        buf.markWriter();

        dst.offerMessage(buf);
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
    private void handleBodyPart(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        if (content instanceof LastHttpContent) {
            this.encodeLastContent(reqCtx, context, (LastHttpContent) content, dst);
        } else {
            this.encodeContent(reqCtx, context, content, dst);
        }
    }

    private void encodeContent(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (reqCtx.chunkedEncoding) {
            this.offerChunkContent(context, body, dst);
        } else {
            this.offerDirectContent(body, dst);
        }
    }

    private void encodeLastContent(HttpContext.EncodeState<HttpRequest> reqCtx, ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<ByteBuf> dst) {
        if (reqCtx.trailerStarted) {
            this.encodeContent(reqCtx, context, lastContent, dst);
            this.offerTrailingHeadersTerminator(context, dst);
        } else if (reqCtx.chunkedEncoding) {
            this.encodeContent(reqCtx, context, lastContent, dst);
            this.offerLastChunk(context, dst);
        } else {
            this.offerDirectContent(lastContent.content(), dst);
        }
        reqCtx.reset();
    }

    private void offerChunkContent(ProtoContext context, ByteBuf body, ProtoSndQueue<ByteBuf> dst) {
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        ByteBuf prefix = context.byteBufAllocator().buffer(16);
        writeHexInt(prefix, body.readableBytes());
        prefix.writeBytes(CRLF, 0, CRLF.length);
        prefix.markWriter();
        dst.offerMessage(prefix);

        dst.offerMessage(body.retain());

        ByteBuf suffix = context.byteBufAllocator().buffer(CRLF.length);
        suffix.writeBytes(CRLF, 0, CRLF.length);
        suffix.markWriter();

        dst.offerMessage(suffix);
    }

    private void offerDirectContent(ByteBuf body, ProtoSndQueue<ByteBuf> dst) {
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        dst.offerMessage(body.retain());
    }

    private void offerLastChunk(ProtoContext context, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf lastBuf = context.byteBufAllocator().buffer(ZERO_CRLF_CRLF.length);
        lastBuf.writeBytes(ZERO_CRLF_CRLF, 0, ZERO_CRLF_CRLF.length);
        lastBuf.markWriter();

        dst.offerMessage(lastBuf);
    }

    private void offerTrailingHeadersTerminator(ProtoContext context, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf buf = context.byteBufAllocator().buffer(CRLF.length);
        buf.writeBytes(CRLF, 0, CRLF.length);
        buf.markWriter();

        dst.offerMessage(buf);
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
}