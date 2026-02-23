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
package net.hasor.neta.codec.http.cors;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * Pipeline handler that provides CORS (Cross-Origin Resource Sharing) support.
 * <h3>How it works</h3>
 * Place this handler <em>after</em> the {@code HttpObjectAggregator} in the decode pipeline so
 * that it receives fully-assembled {@link FullHttpRequest} objects.
 * <ul>
 *   <li><strong>Preflight requests</strong> (OPTIONS + {@code Access-Control-Request-Method}):
 *       A {@link DefaultFullHttpResponse} with {@code 204 No Content} and the appropriate
 *       CORS response headers is placed onto the output queue.  The original request is
 *       <em>not</em> forwarded; downstream handlers never see it.</li>
 *   <li><strong>Non-preflight requests</strong> with an allowed {@code Origin}: the request is
 *       forwarded as-is.  The caller is responsible for adding CORS headers to the actual
 *       response via {@link CorsUtil#applySimpleCorsHeaders}.</li>
 *   <li><strong>Requests without an {@code Origin} header</strong> (same-origin or non-browser):
 *       forwarded unchanged.</li>
 * </ul>
 * <h3>Usage</h3>
 * <pre>
 *   CorsConfig config = CorsConfig.builder()
 *       .allowOrigins("https://example.com")
 *       .allowMethods("GET", "POST")
 *       .allowHeaders("Content-Type", "Authorization")
 *       .allowCredentials(true)
 *       .maxAge(3600)
 *       .build();
 *   ProtoInitializer init = ctx -> {
 *       ctx.addLastDecoder("frame",   new HttpRequestDecoder());
 *       ctx.addLastDecoder("agg",     new HttpObjectAggregator(65536));
 *       ctx.addLastDecoder("cors",    new CorsHandler(config));
 *       ctx.addLastEncoder("enc",     new HttpResponseEncoder());
 *   };
 *   netManager.subscribe(payload -> {
 *       Object msg = payload.getData();
 *       if (msg instanceof FullHttpResponse) {
 *           // Preflight response — send it straight back
 *           payload.source().sendData(msg);
 *           return;
 *       }
 *       FullHttpRequest request = (FullHttpRequest) msg;
 *       FullHttpResponse response = buildResponse(request);
 *       CorsUtil.applySimpleCorsHeaders(request, response, config);
 *       payload.source().sendData(response);
 *   });
 * </pre>
 * @see CorsConfig
 * @see CorsUtil
 */
public class CorsHandler implements ProtoHandler<FullHttpRequest, Object> {
    private final CorsConfig config;

    /**
     * Creates a new {@code CorsHandler} with the given configuration.
     * @param config the CORS configuration; must not be {@code null}
     */
    public CorsHandler(CorsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    /** Returns the CORS configuration used by this handler. */
    public CorsConfig config() {
        return config;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<FullHttpRequest> src, ProtoSndQueue<Object> dst) throws Throwable {
        if (!src.hasMore()) {
            return ProtoStatus.Stop;
        }

        FullHttpRequest request = src.takeMessage();

        // CORS is disabled — forward without modification
        if (!config.isEnabled()) {
            dst.offerMessage(request);
            return ProtoStatus.Next;
        }

        String origin = request.headers().get(HttpHeaderNames.ORIGIN);

        // No Origin header — not a cross-origin request; forward as-is
        if (StringUtils.isBlank(origin)) {
            dst.offerMessage(request);
            return ProtoStatus.Next;
        }

        // Preflight request: OPTIONS + Access-Control-Request-Method
        if (CorsUtil.isPreflightRequest(request)) {
            DefaultFullHttpResponse preflight = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);
            CorsUtil.applyPreflightCorsHeaders(request, preflight, config);
            dst.offerMessage(preflight);
            return ProtoStatus.Next;
        }

        // Normal cross-origin request — forward; caller applies simple CORS headers to the response
        dst.offerMessage(request);
        return ProtoStatus.Next;
    }
}
