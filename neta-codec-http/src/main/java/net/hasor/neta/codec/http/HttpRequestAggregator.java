/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.concurrent.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
/**
 * Incrementally aggregates HTTP request objects into a {@link FullHttpRequest}.
 * Request validation also handles expectations and automatic 413/417 responses.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public class HttpRequestAggregator extends AbstractHttpAggregator<HttpRequest> {
    public HttpRequestAggregator() {
        super();
    }

    public HttpRequestAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpRequest;
    }

    @Override
    protected HttpObject newFullMessage(ProtoContext context, HttpRequest message, DefaultHttpHeaders headers, ByteBuf content) {
        if (context.getConfig().isPrintLog()) {
            logger.info(this.logPrefix() + " channel=" + context.getChannel().getChannelId() +  " " + message.method() + " " + message.uri() + " streamId=" + message.streamId() + " contentLength=" + content.readableBytes());
        }
        return new DefaultFullHttpRequest(message, headers, content);
    }

    @Override
    protected String logPrefix() {
        return "[HTTP-REQ-AGG]";
    }

    /**
     * Validates the completed request head and applies expectation handling.
     */
    @Override
    protected void onHeadersClosed(ProtoContext context, HttpRequest message, HttpHeaders headers, long contentLength) {
        if (contentLength > this.maxContentLength()) {
            this.sendAutoResponse(context, message.protocolVersion(), message.streamId(), HttpStatus.REQUEST_ENTITY_TOO_LARGE, this.isKeepAlive(message, headers));
            this.enterDiscardMode();
            return;
        }
        this.handleExpectation(context, message, headers);
    }

    /**
     * Sends a 413 response and switches to discard mode once the request body grows beyond the configured limit.
     */
    @Override
    protected boolean onContentTooLarge(ProtoContext context, HttpRequest message, HttpHeaders headers, int newLength) {
        this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE, headers);
        this.enterDiscardMode();
        return true;
    }

    /**
     * Converts request-side protocol failures into HTTP auto-responses when enough request context is available.
     */
    @Override
    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError, HttpRequest request, HttpHeaders headers) {
        if (request != null) {
            this.sendAutoResponse(context, request, e.status(), headers);
            this.enterDiscardMode();
            return true;
        }

        HttpContext httpContext = HttpContext.getOrCreate(context);
        HttpContext.InboundMessageType inboundType = fromPipelineError ? httpContext.consumeInboundErrorType() : null;
        if (inboundType == HttpContext.InboundMessageType.REQUEST) {
            this.sendAutoResponse(context, HttpVersion.HTTP_1_1, 0, e.status(), false);
            this.resetAggregation();
            return true;
        }

        this.resetAggregation();
        return false;
    }

    /**
     * Processes the {@code Expect} header after the request head has been fully assembled.
     */
    private void handleExpectation(ProtoContext context, HttpRequest request, HttpHeaders headers) {
        boolean expectsContinue;
        if (headers instanceof DefaultHttpHeaders) {
            DefaultHttpHeaderEntry entry = ((DefaultHttpHeaders) headers).findFirstEntry(HttpHeaderNames.EXPECT);
            if (entry == null || !entry.hasNonBlankValue()) {
                return;
            }
            expectsContinue = entry.valueEqualsIgnoreCase(HttpHeaderValues.CONTINUE);
        } else {
            String value = headers != null ? headers.getString(HttpHeaderNames.EXPECT) : null;
            if (HttpCharSequences.isBlank(value)) {
                return;
            }
            expectsContinue = HttpCharSequences.equalsIgnoreCase(value.trim(), HttpHeaderValues.CONTINUE);
        }
        if (!expectsContinue) {
            this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.EXPECTATION_FAILED, this.isKeepAlive(request, headers));
            this.enterDiscardMode();
            return;
        }

        this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.CONTINUE, true);
    }

    /**
     * Sends an automatic response that reuses the current request version and keep-alive policy.
     */
    private void sendAutoResponse(ProtoContext context, HttpRequest request, HttpStatus status, HttpHeaders headers) {
        this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), status, this.isKeepAlive(request, headers));
    }

    private boolean isKeepAlive(HttpRequest request, HttpHeaders headers) {
        if (headers instanceof DefaultHttpHeaders) {
            DefaultHttpHeaderEntry entry = ((DefaultHttpHeaders) headers).findFirstEntry(HttpHeaderNames.CONNECTION);
            if (entry != null) {
                if (entry.valueContainsIgnoreCase(HttpHeaderValues.CLOSE)) {
                    return false;
                }
                if (entry.valueContainsIgnoreCase(HttpHeaderValues.KEEP_ALIVE)) {
                    return true;
                }
            }
        } else {
            String value = headers != null ? headers.getString(HttpHeaderNames.CONNECTION) : null;
            if (HttpCharSequences.containsIgnoreCase(value, HttpHeaderValues.CLOSE)) {
                return false;
            }
            if (HttpCharSequences.containsIgnoreCase(value, HttpHeaderValues.KEEP_ALIVE)) {
                return true;
            }
        }
        return request.protocolVersion().isKeepAliveDefault();
    }

    private void sendAutoResponse(ProtoContext context, HttpVersion protocolVersion, long streamId, HttpStatus status, boolean keepAlive) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(protocolVersion, status, ByteBuf.wrap(new byte[0]));
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);

        if (!keepAlive) {
            response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        Future<?> sendFuture = context.sendData(response);
        try {
            if (sendFuture != null) {
                sendFuture.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to send auto response", e);
        }
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " auto-response status=" + status.code() + " keepAlive=" + keepAlive + " streamId=" + streamId);
        }
    }

}
