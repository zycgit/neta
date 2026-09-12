/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.routing;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.codec.ssl.SslContext;
/**
 * HTTPS ALPN route selector.
 * <p>
 * This selector reads the ALPN result from {@link SslContext} after the TLS handshake completes and returns the HTTP/1.1 or HTTP/2 branch key.
 * This selector uses the ALPN result as its only routing signal.
 * <p>
 * Overall flow:
 * <pre>
 *   inbound TCP/TLS connection
 *              v
 *        [ SslDuplex ]
 *              v
 *   +------------------------+
 *   | Http2OverTlsRoute      |
 *   | read SslContext ALPN   |
 *   +------------------------+
 *        |             |
 *        |             +--> BRANCH_H1 --> HttpServerDuplexe --> HttpRequestAggregator --> handler
 *        |
 *        +----------------> BRANCH_H2 --> Http2FrameDuplex --> Http2ObjectDuplex --> HttpServerDuplexeAggregator --> handler
 * </pre>
 * <p>
 * Decision rules:
 * <ul>
 *   <li>returns {@code null} when {@link SslContext#isReady()} returns {@code false};</li>
 *   <li>returns {@value #BRANCH_H2} when the ALPN result is {@code "h2"};</li>
 *   <li>returns {@value #BRANCH_H1} for all other negotiated results.</li>
 * </ul>
 * <p>
 * Typical usage example:
 * <pre>
 *   ProtoRoutingBuilder&lt;ByteBuf, ByteBuf&gt; alpn = ProtoHelper.typedRoutingAsStatic(new Http2OverTlsRoute());
 *   alpn.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -&gt; {
 *       branch.addLast("h2-frame", new Http2FrameDuplex(true));
 *       branch.addLast("h2-object", new Http2ObjectDuplex(true));
 *       branch.nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -&gt; partition.policy(new Http2ObjectPartitionPolicy()).byInitializer(partitionCtx -&gt; partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576))));
 *       branch.addLastDecoder("h2-handler", new HttpDispatchHandler(true));
 *   });
 *   alpn.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -&gt; {
 *       branch.addLast("http-codec", new HttpServerDuplexe(4096, 8192, 8192));
 *       branch.addLastDecoder("http-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("http-handler", new HttpDispatchHandler(true));
 *   });
 *   ctx.addLast("ssl", new SslDuplex(sslConfig));
 *   ctx.addLast("alpn-router", alpn.build());
 * </pre>
 * <p>
 * This selector applies to TLS entries that use the ALPN result as the only routing signal.
 * Use {@link HttpAggregatorOverTlsRoute} when the route also needs to inspect the decrypted HTTP first packet.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 * @see HttpAggregatorOverTlsRoute
 * @see SslContext
 */
public class Http2OverTlsRoute implements ProtoRoutingDataSelector<ByteBuf, ByteBuf>, HttpRouteKey {
    private static final Logger logger = Logger.getLogger(Http2OverTlsRoute.class);

    /**
     * Returns the HTTP branch key according to the ALPN result after the TLS handshake.
     */
    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> sndDown) {
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
