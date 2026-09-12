/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import java.nio.charset.StandardCharsets;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.Http2GoawayEvent;
import net.hasor.neta.codec.http.h2.Http2ResetEvent;
import net.hasor.nhttp.server.ServerConfig;
import net.hasor.nhttp.server.internal.InternalBodyChannel;

/**
 * IO-thread HTTP streaming request handler.
 *
 * <h3>Message flow</h3>
 * <p>The neta HTTP codec emits an ordered sequence of {@link HttpObject}s for each request:</p>
 * <ol>
 *   <li>{@link HttpRequest} — request line (method + URI + version)</li>
 *   <li>Zero or more {@link HttpHeaders} — header blocks</li>
 *   <li>{@link LastHttpHeaders} — marks the end of the header section</li>
 *   <li>Zero or more {@link HttpContent} — body chunks</li>
 *   <li>{@link LastHttpContent} — marks the end of the body (also an {@link HttpContent})</li>
 * </ol>
 *
 * <p>This handler accumulates all header blocks (step 2–3), and fires
 * {@link RequestDispatchCallback#onHttpRequest} once {@link LastHttpHeaders} arrives (step 3),
 * providing both the request line and the fully-assembled headers. Subsequent body chunks
 * (steps 4–5) are fed into the {@link InternalBodyChannel}.</p>
 *
 * <h3>maxContentLength guard</h3>
 * <p>When {@link ServerConfig#getMaxContentLength()} is positive, running byte count is tracked
 * across all {@link HttpContent} chunks. If the total exceeds that limit, the body channel is
 * closed and a 413 response is sent immediately. Non-positive values disable transport-level
 * request body size enforcement.</p>
 *
 * <h3>HTTP/2 support</h3>
 * <p>For HTTP/2, neta's per-stream partition ensures one instance per stream. Stream close
 * events ({@link Http2ResetEvent}, {@link Http2GoawayEvent}) close the body channel to unblock
 * the worker thread.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class HttpRequestHandler implements ProtoHandler<HttpObject, Object> {
    private static final Logger logger = Logger.getLogger(HttpRequestHandler.class);

    private final boolean                 secure;
    private final ServerConfig            config;
    private final RequestDispatchCallback callback;

    // Per-request mutable state — reset on each new HttpRequest
    private HttpRequest         pendingRequestLine;
    private DefaultHttpHeaders  accumulatedHeaders;
    private InternalBodyChannel currentBodyChannel;
    private HttpVersion         activeProtocolVersion;
    private long                activeStreamId     = -1;
    private boolean             requestTerminated  = false;
    private long                receivedBodyBytes = 0;

    HttpRequestHandler(boolean secure, ServerConfig config, RequestDispatchCallback callback) {
        this.secure = secure;
        this.config = config;
        this.callback = callback;
    }

    // =========================================================================
    // ProtoHandler
    // =========================================================================

    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
        while (src.hasMore()) {
            HttpObject obj = src.takeMessage();
            if (obj == null) {
                continue;
            }

            if (obj instanceof FullHttpRequest) {
                onFullRequest(ctx, (FullHttpRequest) obj);
            } else if (obj instanceof HttpRequest && !(obj instanceof HttpHeaders)) {
                // Start of a new request: save the request line, reset state
                onRequestLine(obj);
            } else if (obj instanceof LastHttpHeaders) {
                // End of headers section: fire the callback now
                onLastHeaders(ctx, (LastHttpHeaders) obj);
            } else if (obj instanceof HttpHeaders) {
                // A header block (not the last one): accumulate
                onHeaderBlock((HttpHeaders) obj);
            } else if (obj instanceof HttpContent) {
                // Body chunk (LastHttpContent is also an HttpContent)
                onBodyChunk(ctx, (HttpContent) obj);
            }
        }
        return ProtoStatus.Next;
    }

    // =========================================================================
    // Per-object handlers
    // =========================================================================

    /** Resets per-request state when the request line arrives. */
    private void onRequestLine(HttpObject obj) {
        this.pendingRequestLine = (HttpRequest) obj;
        this.accumulatedHeaders = new DefaultHttpHeaders();
        resetRequestState();
        this.activeProtocolVersion = this.pendingRequestLine.protocolVersion();
        this.activeStreamId = this.pendingRequestLine.streamId();
        closeBodyChannelIfPresent(); // safety: previous channel should already be closed
    }

    /** Appends a header block to the accumulated headers. */
    private void onHeaderBlock(HttpHeaders block) {
        if (this.accumulatedHeaders != null) {
            this.accumulatedHeaders.appendHeaders(block);
        }
    }

    /**
     * End of headers — fire the callback with the fully assembled request line + headers.
     * Also creates the {@link InternalBodyChannel} and the {@link ResponseSink}.
     */
    private void onLastHeaders(ProtoContext ctx, LastHttpHeaders last) {
        if (this.pendingRequestLine == null) {
            return; // guard: should not happen in normal flow
        }

        // Append any trailing headers carried by LastHttpHeaders itself
        if (this.accumulatedHeaders != null) {
            this.accumulatedHeaders.appendHeaders(last);
        }

        HttpRequest line = this.pendingRequestLine;
        HttpHeaders headers = this.accumulatedHeaders;
        this.pendingRequestLine = null;
        this.accumulatedHeaders = null;
        this.activeProtocolVersion = line.protocolVersion();
        this.activeStreamId = line.streamId();
        this.requestTerminated = false;

        if (rejectAtHeadersIfNeeded(ctx, line, headers)) {
            return;
        }

        NetChannel channel = (NetChannel) ctx.getChannel();
        this.currentBodyChannel = new InternalBodyChannel(//
                this.config.getBodyQueueCapacity(), //
                this.config.getBackpressureStrategy(), //
                channel);

        ResponseSink sink = createResponseSink(ctx, line);

        try {
            this.callback.onHttpRequest(ctx, line, headers, this.currentBodyChannel, sink, channel, this.secure);
        } catch (Throwable e) {
            logger.warn("RequestDispatchCallback.onHttpRequest() threw an exception", e);
            closeBodyChannelIfPresent();
        }
    }

    /** Handles an aggregated request that already carries headers and terminal body content. */
    private void onFullRequest(ProtoContext ctx, FullHttpRequest request) {
        closeBodyChannelIfPresent();

        this.activeProtocolVersion = request.protocolVersion();
        this.activeStreamId = request.streamId();
        this.requestTerminated = false;
        this.receivedBodyBytes = request.content().readableBytes();
        if (isContentLengthLimited() && this.receivedBodyBytes > this.config.getMaxContentLength()) {
            request.release();
            sendErrorAndClose(ctx, 413, "Payload Too Large");
            resetRequestState();
            return;
        }

        NetChannel channel = (NetChannel) ctx.getChannel();
        this.currentBodyChannel = new InternalBodyChannel(//
                this.config.getBodyQueueCapacity(), //
                this.config.getBackpressureStrategy(), //
                channel);

        ResponseSink sink = createResponseSink(ctx, request);
        try {
            this.callback.onHttpRequest(ctx, request, request, this.currentBodyChannel, sink, channel, this.secure);
        } catch (Throwable e) {
            logger.warn("RequestDispatchCallback.onHttpRequest() threw an exception", e);
            closeBodyChannelIfPresent();
            request.release();
            this.receivedBodyBytes = 0;
            return;
        }

        boolean accepted = this.currentBodyChannel.offer(request);
        if (!accepted) {
            sendErrorAndClose(ctx, 503, "Service Unavailable");
        }

        this.currentBodyChannel = null;
        resetRequestState();
    }

    /** Feeds a body chunk into the active {@link InternalBodyChannel}. */
    private void onBodyChunk(ProtoContext ctx, HttpContent content) {
        if (this.requestTerminated) {
            boolean lastChunk = content instanceof LastHttpContent;
            content.release();
            if (lastChunk) {
                resetRequestState();
            }
            return;
        }

        // ① maxContentLength guard
        this.receivedBodyBytes += content.content().readableBytes();
        if (isContentLengthLimited() && this.receivedBodyBytes > this.config.getMaxContentLength()) {
            closeBodyChannelIfPresent();
            this.requestTerminated = true;
            boolean lastChunk = content instanceof LastHttpContent;
            content.release();
            sendErrorAndClose(ctx, 413, "Payload Too Large");
            if (lastChunk) {
                resetRequestState();
            }
            return;
        }

        if (this.currentBodyChannel != null) {
            boolean accepted = this.currentBodyChannel.offer(content);
            if (!accepted) {
                // Back-pressure strategy gave up — send 503
                this.requestTerminated = true;
                sendErrorAndClose(ctx, 503, "Service Unavailable");
                this.currentBodyChannel = null;
                return;
            }
            if (content instanceof LastHttpContent) {
                // Body complete; worker thread now drains the channel
                this.currentBodyChannel = null;
                resetRequestState();
            }
        } else {
            content.release();
        }
    }

    // =========================================================================
    // Lifecycle callbacks
    // =========================================================================

    /**
     * HTTP/2 stream lifecycle events — close the body channel to unblock the worker.
     */
    @Override
    public boolean onEvent(ProtoContext ctx, SoEvent event) throws Throwable {
        Object data = event.getData();
        if (data instanceof Http2ResetEvent || data instanceof Http2GoawayEvent) {
            closeBodyChannelIfPresent();
        }
        return true;
    }

    /** TCP close (HTTP/1.1) or partition close (HTTP/2). */
    @Override
    public void onClose(ProtoContext ctx) {
        closeBodyChannelIfPresent();
    }

    @Override
    public ProtoStatus onError(ProtoContext ctx, Throwable e, ProtoExceptionHolder eh) {
        closeBodyChannelIfPresent();
        if (e instanceof HttpProtocolException) {
            int code = protocolExceptionToStatusCode(e);
            String msg = protocolExceptionToMessage(e);
            logger.warn("HTTP protocol error [" + code + "]: " + e.getMessage());
            sendErrorAndClose(ctx, code, msg);
            eh.clear();
            return ProtoStatus.Stop;
        }
        return ProtoStatus.Next;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Creates the appropriate {@link ResponseSink} for the given request.
     * HTTP/2 requests carry a positive {@code streamId()} and use HTTP/2 DATA frames;
     * all other requests use HTTP/1.1 framing.
     */
    private ResponseSink createResponseSink(ProtoContext ctx, HttpRequest line) {
        if (line.protocolVersion() == HttpVersion.HTTP_2_0) {
            return new Http2ResponseSink(ctx, line.streamId());
        } else {
            return new Http1ResponseSink(ctx, line.protocolVersion());
        }
    }

    private void closeBodyChannelIfPresent() {
        if (this.currentBodyChannel != null) {
            this.currentBodyChannel.close();
            this.currentBodyChannel = null;
        }
    }

    private boolean rejectAtHeadersIfNeeded(ProtoContext ctx, HttpRequest line, HttpHeaders headers) {
        long contentLength = headers != null ? headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1) : -1;
        String expect = headers != null ? headers.getString(HttpHeaderNames.EXPECT) : null;
        HttpVersion version = line != null ? line.protocolVersion() : this.activeProtocolVersion;
        long streamId = line != null ? line.streamId() : this.activeStreamId;
        boolean contentLengthLimited = isContentLengthLimited();

        if (expect != null) {
            String expectValue = expect.trim();
            if (!expectValue.isEmpty()) {
                if (!HttpHeaderValues.CONTINUE.equalsIgnoreCase(expectValue)) {
                    this.requestTerminated = true;
                    sendErrorAndClose(ctx, 417, "Expectation Failed");
                    return true;
                }
                if (contentLengthLimited && contentLength >= 0 && contentLength > this.config.getMaxContentLength()) {
                    this.requestTerminated = true;
                    sendErrorAndClose(ctx, 413, "Payload Too Large");
                    return true;
                }
                sendContinueResponse(ctx, version, streamId);
            }
        }

        if (contentLengthLimited && contentLength >= 0 && contentLength > this.config.getMaxContentLength()) {
            this.requestTerminated = true;
            sendErrorAndClose(ctx, 413, "Payload Too Large");
            return true;
        }

        return false;
    }

    private boolean isContentLengthLimited() {
        return this.config.getMaxContentLength() > 0;
    }

    private void sendContinueResponse(ProtoContext ctx, HttpVersion version, long streamId) {
        try {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(version != null ? version : HttpVersion.HTTP_1_1, HttpStatus.CONTINUE, ByteBuf.EMPTY);
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
            if (streamId > 0) {
                response.streamId(streamId);
            }
            ctx.sendEncoded(response);
        } catch (Throwable t) {
            logger.warn("Failed to send 100-continue response", t);
            ctx.getChannel().closeNow();
        }
    }

    private void resetRequestState() {
        this.receivedBodyBytes = 0;
        this.requestTerminated = false;
        this.activeProtocolVersion = null;
        this.activeStreamId = -1;
    }

    private void sendErrorAndClose(ProtoContext ctx, int code, String message) {
        try {
            String body = "<html><body><h1>" + code + " " + htmlEscape(message) + "</h1></body></html>";
            ByteBuf content = ByteBuf.wrap(body.getBytes(StandardCharsets.UTF_8));
            HttpVersion version = this.activeProtocolVersion != null ? this.activeProtocolVersion : HttpVersion.HTTP_1_1;
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(version, HttpStatus.valueOf(code), content);
            if (this.activeStreamId > 0) {
                response.streamId(this.activeStreamId);
            }
            response.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(content.readableBytes()));
            if (version.majorVersion() < 2) {
                response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.sendEncoded(response).onFinal(f -> ctx.getChannel().closeNow());
            } else {
                response.removeHeader(HttpHeaderNames.CONNECTION);
                ctx.sendEncoded(response);
            }
        } catch (Throwable t) {
            logger.warn("Failed to send error response", t);
            ctx.getChannel().closeNow();
        }
    }

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int protocolExceptionToStatusCode(Throwable e) {
        if (e instanceof HttpInitialLineTooLongException) {
            return 414;
        } else if (e instanceof HttpHeaderTooLargeException) {
            return 431;
        } else if (e instanceof HttpContentTooLargeException) {
            return 413;
        } else {
            return 400;
        }
    }

    private static String protocolExceptionToMessage(Throwable e) {
        if (e instanceof HttpInitialLineTooLongException) {
            return "URI Too Long";
        } else if (e instanceof HttpHeaderTooLargeException) {
            return "Request Header Fields Too Large";
        } else if (e instanceof HttpContentTooLargeException) {
            return "Content Too Large";
        } else {
            return "Bad Request";
        }
    }
}
