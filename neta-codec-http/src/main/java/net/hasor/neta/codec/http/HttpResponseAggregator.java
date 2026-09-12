/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;

/**
 * Incrementally aggregates HTTP response objects into a {@link FullHttpResponse}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public class HttpResponseAggregator extends AbstractHttpAggregator<HttpResponse> {
    public HttpResponseAggregator() {
        super();
    }

    public HttpResponseAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpResponse;
    }

    @Override
    protected HttpObject newFullMessage(ProtoContext context, HttpResponse message, DefaultHttpHeaders headers, ByteBuf content) {
        if (context.getConfig().isPrintLog()) {
            logger.info(this.logPrefix() + " channel=" + context.getChannel().getChannelId() + " response status=" + message.status().code() + " streamId=" + message.streamId() + " contentLength=" + content.readableBytes());
        }
        return new DefaultFullHttpResponse(message, headers, content);
    }

    @Override
    protected String logPrefix() {
        return "[HTTP-RESP-AGG]";
    }
}
