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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoRouting;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * ALPN (Application-Layer Protocol Negotiation) based routing for HTTPS connections.
 * <p>
 * After TLS handshake completion, inspects the negotiated application protocol from
 * {@link SslContext#getApplicationProtocol()} and routes to the corresponding branch:
 * <ul>
 *   <li>{@value #BRANCH_H2} — HTTP/2 over TLS (h2, RFC 9113)</li>
 *   <li>The configured default branch — typically HTTP/1.1 (fallback)</li>
 * </ul>
 * <p>
 * This routing operates in the <b>onActive phase</b> (context-based, no data required).
 * It waits until the SSL handshake is complete ({@code SslContext.isReady()}) before
 * making a routing decision. If the handshake is not yet complete, it returns {@code null}
 * to defer the decision to the next invocation.
 * <p>
 * <b>Typical usage:</b> placed inside a TLS branch, after the {@code SslDuplexer}:
 * <pre>
 *   // Inside a TLS branch, after SslDuplexer
 *   HttpAlpnRouting alpnRouting = new HttpAlpnRouting("http/1.1");
 *   ProtoRoutingDuplexer.Builder&lt;ByteBuf&gt; alpnBuilder = ProtoRoutingDuplexer.newBuilder(alpnRouting);
 *   alpnBuilder.branch(HttpAlpnRouting.BRANCH_H2, h2Branch -&gt; {
 *       h2Branch.addLast("h2-codec", new Http2ServerDuplexe());
 *       h2Branch.addLastDecoder("h2-aggregator", new HttpObjectAggregator(1048576));
 *   });
 *   alpnBuilder.branch("http/1.1", httpBranch -&gt; {
 *       httpBranch.addLast("http-codec", new HttpServerDuplexe());
 *       httpBranch.addLastDecoder("http-aggregator", new HttpObjectAggregator(1048576));
 *   });
 *   tlsBranch.addLast("alpn-router", alpnBuilder.build(tlsBranch));
 * </pre>
 * @see Http2PrefaceRouting
 * @see SslContext
 */
public class HttpsAlpnRouting implements ProtoRouting<ByteBuf> {
    /** Branch key for HTTP/2 over TLS (ALPN protocol identifier "h2"). */
    public static final String BRANCH_H2 = "h2";
    private final       String defaultBranch;

    /**
     * Creates an ALPN routing with the specified default branch.
     * <p>
     * When the negotiated ALPN protocol is "h2", the {@link #BRANCH_H2} branch key is returned.
     * For all other negotiated protocols (including "http/1.1", null, or unknown),
     * the {@code defaultBranch} is returned.
     * @param defaultBranch branch key to use when the ALPN result is not "h2" (e.g. "http/1.1")
     * @throws IllegalArgumentException if defaultBranch is null or empty
     */
    public HttpsAlpnRouting(String defaultBranch) {
        if (defaultBranch == null || defaultBranch.isEmpty()) {
            throw new IllegalArgumentException("defaultBranch must not be null or empty.");
        }
        this.defaultBranch = defaultBranch;
    }

    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Object> rcvDown) {
        SslContext sslCtx = context.context(SslContext.class);
        if (sslCtx == null || !sslCtx.isReady()) {
            return null; // SSL handshake not complete, wait
        }

        String protocol = sslCtx.getApplicationProtocol();
        if (StringUtils.equals("h2", protocol)) {
            return BRANCH_H2;
        }

        return this.defaultBranch;
    }
}
