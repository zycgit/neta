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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
/**
 * HTTPS aggregate route selector.
 * <p>
 * This selector reads the ALPN result after the TLS handshake completes and returns the route branch key according to the negotiated protocol and the decrypted HTTP first packet.
 * This selector can determine HTTP/1.1, HTTP/2 prior knowledge, and h2c upgrade traffic on the same HTTPS entry.
 * <p>
 * Overall flow:
 * <pre>
 *   inbound TCP/TLS bytes
 *           v
 *     [ SslDuplex ]
 *           v
 *   +-----------------------------+
 *   | HttpAggregatorOverTlsRoute  |
 *   | inspect TLS ALPN first      |
 *   | inspect decrypted HTTP next |
 *   +-----------------------------+
 *      |            |            |
 *      |            |            +--> BRANCH_H1  --> HttpServerDuplexe --> HttpRequestAggregator --> handler
 *      |            |
 *      |            +---------------> BRANCH_H2C --> HttpServerDuplexe --> H2CUpgradeServerDuplexe --> handler
 *      |
 *      +----------------------------> BRANCH_H2  --> Http2FrameDuplex --> Http2ObjectDuplex --> HttpServerDuplexeAggregator --> handler
 * </pre>
 * The h2c path on an HTTPS entry uses the already decrypted HTTP/1.1 upgrade request inside TLS as the switching seed.
 * The following preface, SETTINGS, and SETTINGS ACK frames enter the switched HTTP/2 processing path.
 * <p>
 * Decision rules:
 * <ul>
 *   <li>returns {@code null} when the TLS handshake is not ready;</li>
 *   <li>returns {@value #BRANCH_H2} when the ALPN result is {@code "h2"};</li>
 *   <li>continues with the cleartext first-packet detection logic from {@link HttpAggregatorRoute} when the ALPN result lands on the HTTP/1.1 side;</li>
 *   <li>returns {@value #BRANCH_H1}, {@value #BRANCH_H2}, or {@value #BRANCH_H2C} according to that detection result.</li>
 * </ul>
 * <p>
 * Typical usage example:
 * <pre>
 *   ProtoHelper.standard()
 *       .nextDuplex("ssl", new SslDuplex(sslConfig))
 *       .nextRouteAsStatic("alpn", new HttpAggregatorOverTlsRoute(), routing -&gt; {
 *           ProtoRoutingControl routingControl = routing.control();
 *           routing.branch(HttpRouteKey.BRANCH_H1, branch -&gt; branch
 *               .nextDuplex("http-codec", new HttpServerDuplexe())
 *               .nextDecoder("http-aggregator", new HttpRequestAggregator(1048576))
 *               .nextDecoder("http-handler", handler));
 *           routing.branch(HttpRouteKey.BRANCH_H2, branch -&gt; branch
 *               .nextDuplex("h2-frame", new Http2FrameDuplex(true))
 *               .nextDuplex("h2-message", new Http2ObjectDuplex(true, routingControl))
 *               .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -&gt; {
 *                   Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
 *                   ProtoPartitionControl partitionControl = partition.control();
 *                   partition.policy(policy).byDefault(p2 -&gt; {
 *                       p2.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(partitionControl, policy));
 *                   }).byInitializer(partitionCtx -&gt; {
 *                       partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576));
 *                       partitionCtx.addLastDecoder("h2-handler", handler);
 *                   });
 *               }));
 *           routing.branch(HttpRouteKey.BRANCH_H2C, branch -&gt; branch
 *               .nextDuplex("http-codec", new HttpServerDuplexe())
 *               .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl))
 *               .nextDecoder("h2c-handler", handler));
 *       })
 *       .config(ctx);
 * </pre>
 * <p>
 * This selector applies to HTTPS single-port multiplexed entries.
 * Use {@link Http2OverTlsRoute} when routing depends only on ALPN.
 * Use {@link HttpAggregatorRoute} for cleartext TCP entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-08
 * @see Http2OverTlsRoute
 * @see HttpAggregatorRoute
 */
public class HttpAggregatorOverTlsRoute implements ProtoRoutingDataSelector<ByteBuf, ByteBuf>, HttpRouteKey {
    private final Http2OverTlsRoute   tlsRoute  = new Http2OverTlsRoute();
    private final HttpAggregatorRoute httpRoute = new HttpAggregatorRoute();

    /**
     * Returns the branch key according to the TLS ALPN result and the decrypted HTTP first packet.
     */
    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> sndDown) {
        String tlsBranch = this.tlsRoute.route(context, rcvUp, sndDown);
        if (tlsBranch == null) {
            return null; // need ssl ready.
        }

        if (BRANCH_H1.equals(tlsBranch)) {
            return this.httpRoute.route(context, rcvUp, sndDown);
        } else {
            return BRANCH_H2;
        }
    }
}