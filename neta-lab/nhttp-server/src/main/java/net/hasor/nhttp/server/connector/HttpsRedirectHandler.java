/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.connector;

import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/**
 * IO-thread handler that responds to plaintext HTTP requests arriving on the HTTPS
 * port with a {@code 301 Moved Permanently} redirect to the same URL using the
 * {@code https} scheme.
 *
 * <p>This handler is inserted into the <em>plaintext</em> branch of the TLS-detect
 * routing in {@link PipelineFactory}. It operates directly on the {@link HttpObject}
 * stream (no aggregator required) — only the {@link HttpRequest} headers are needed
 * to construct the redirect URL. Subsequent {@link HttpContent} chunks are released
 * without processing.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class HttpsRedirectHandler implements ProtoHandler<HttpObject, Object> {
    private final String serverName;
    private HttpRequest  pendingRequest;
    private HttpHeaders  pendingHeaders;

    HttpsRedirectHandler(String serverName) {
        this.serverName = serverName;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg instanceof HttpRequest && !(msg instanceof HttpHeaders)) {
                this.pendingRequest = (HttpRequest) msg;
                this.pendingHeaders = new DefaultHttpHeaders();
            } else if (msg instanceof HttpHeaders) {
                if (this.pendingHeaders != null) {
                    this.pendingHeaders.appendHeaders((HttpHeaders) msg);
                }
                if (msg instanceof LastHttpHeaders && this.pendingRequest != null) {
                    sendRedirect(ctx, this.pendingRequest, this.pendingHeaders);
                    this.pendingRequest = null;
                    this.pendingHeaders = null;
                }
            } else if (msg instanceof HttpContent) {
                msg.release();
            }
        }
        return ProtoStatus.Stop;
    }

    private void sendRedirect(ProtoContext ctx, HttpRequest request, HttpHeaders headers) {
        String host = headers != null ? headers.getString(HttpHeaderNames.HOST) : null;
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        String redirectUrl = "https://" + host + request.uri();

        String bodyText = "<html><body><h1>301 Moved Permanently</h1>" + "<p>Redirecting to <a href=\"" + htmlEscape(redirectUrl) + "\">" + htmlEscape(redirectUrl) + "</a></p></body></html>";
        ByteBuf content = ByteBuf.wrap(bodyText.getBytes(StandardCharsets.UTF_8));

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.MOVED_PERMANENTLY, content);
        response.setHeader(HttpHeaderNames.LOCATION, redirectUrl);
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(content.readableBytes()));
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (this.serverName != null && !this.serverName.isEmpty()) {
            response.setHeader(HttpHeaderNames.SERVER, this.serverName);
        }

        ctx.sendEncoded(response).onFinal(f -> ctx.getChannel().close());
    }

    @Override
    public ProtoStatus onError(ProtoContext ctx, Throwable e, ProtoExceptionHolder eh) {
        ctx.getChannel().close();
        eh.clear();
        return ProtoStatus.Stop;
    }

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
