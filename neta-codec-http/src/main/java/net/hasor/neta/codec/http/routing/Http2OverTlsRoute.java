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
package net.hasor.neta.codec.http.routing;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoRoutingDataSelector;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * ALPN-based protocol routing for HTTPS connections.
 * <p>
 * This selector does <b>not</b> inspect HTTP bytes. It waits for the TLS handshake
 * to finish, reads the negotiated ALPN protocol from {@link SslContext}, and then
 * chooses the downstream HTTP protocol branch.
 * <p>
 * Overall flow:
 * <pre>
 *   inbound TCP/TLS connection
 *              |
 *              v
 *        [ SslDuplexer ]
 *              |
 *              v
 *   +------------------------+
 *   | Http2OverTlsRoute      |
 *   | read SslContext ALPN   |
 *   +------------------------+
 *        |             |
 *        |             +--> BRANCH_H1 --> HttpServerDuplexe  --> HttpRequestAggregator --> handler
 *        |
 *        +----------------> BRANCH_H2 --> Http2FrameDuplexe --> Http2ObjectDuplexe --> HttpRequestAggregator --> handler
 * </pre>
 * <p>
 * Decision rules:
 * <ul>
 *   <li>returns {@code null} until {@link SslContext#isReady()} becomes {@code true};</li>
 *   <li>returns {@value #BRANCH_H2} when ALPN negotiated protocol is {@code "h2"};</li>
 *   <li>returns {@value #BRANCH_H1} for all other negotiated or fallback cases.</li>
 * </ul>
 * <p>
 * Typical usage demo:
 * <pre>
 *   ProtoRoutingBuilder&lt;ByteBuf, ByteBuf&gt; alpn = ProtoHelper.typedRoutingAsStatic(new Http2OverTlsRoute());
 *   alpn.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -&gt; {
 *       branch.addLast("h2-frame", new Http2FrameDuplexe(true));
 *       branch.addLast("h2-object", new Http2ObjectDuplexe(true));
 *       branch.addLastDecoder("h2-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("h2-handler", new HttpDispatchHandler(true));
 *   });
 *   alpn.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -&gt; {
 *       branch.addLast("http-codec", new HttpServerDuplexe(4096, 8192, 8192));
 *       branch.addLastDecoder("http-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("http-handler", new HttpDispatchHandler(true));
 *   });
 *   ctx.addLast("ssl", new SslDuplexer(sslConfig));
 *   ctx.addLast("alpn-router", alpn.build());
 * </pre>
 * <p>
 * Use this selector only inside a TLS branch. For cleartext HTTP/1.1, h2 prior knowledge,
 * and h2c upgrade detection, use {@link HttpAggregatorRoute} instead.
 * @see HttpAggregatorRoute
 * @see SslContext
 */
public class Http2OverTlsRoute implements ProtoRoutingDataSelector<ByteBuf, ByteBuf>, HttpRouteKey {
    private static final Logger logger = Logger.getLogger(Http2OverTlsRoute.class);

    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
        boolean printLog = context.getConfig().isPrintLog();
        SslContext sslCtx = context.context(SslContext.class);
        if (sslCtx == null || !sslCtx.isReady()) {
            return null; // SSL handshake not complete, wait
        }

        String protocol = sslCtx.getApplicationProtocol();
        String branch;
        if (StringUtils.equals("h2", protocol)) {
            branch = BRANCH_H2;
        } else {
            branch = BRANCH_H1;
        }

        if (printLog) {
            logger.info("[ALPN] channel=" + context.getChannel().getChannelId() + " protocol='" + protocol + "' -> branch='" + branch + "'");
        }

        return branch;
    }
}
