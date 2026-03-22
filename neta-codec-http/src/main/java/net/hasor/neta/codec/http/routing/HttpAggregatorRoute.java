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
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoRoutingDataSelector;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;

/**
 * Cleartext HTTP protocol routing for HTTP/1.1, HTTP/2 prior knowledge, and h2c upgrade.
 * <p>
 * This selector works on <b>received bytes</b> before any HTTP codec branch is chosen.
 * It can therefore distinguish three cleartext entry modes on the same TCP port:
 * normal HTTP/1.1, direct HTTP/2 preface, and HTTP/1.1 upgrade to h2c.
 * <p>
 * Overall flow:
 * <pre>
 *   inbound TCP bytes
 *         |
 *         v
 *   +------------------------+
 *   | HttpAggregatorRoute    |
 *   | inspect first bytes    |
 *   | inspect request head   |
 *   +------------------------+
 *      |          |          |
 *      |          |          +--> BRANCH_H1  --> HttpServerDuplexe       --> HttpRequestAggregator --> handler
 *      |          |
 *      |          +-------------> BRANCH_H2C --> H2cUpgradeServerDuplexe --> HttpRequestAggregator --> handler
 *      |
 *      +------------------------> BRANCH_H2  --> Http2ServerDuplexe      --> HttpRequestAggregator --> handler
 * </pre>
 * <p>
 * Decision rules:
 * <ul>
 *   <li>returns {@code null} when there are not enough bytes yet to decide;</li>
 *   <li>returns {@value #BRANCH_H2} when the first four bytes are {@code "PRI "};</li>
 *   <li>returns {@value #BRANCH_H2C} when a complete HTTP/1.1 request head contains a valid
 *       {@code Upgrade: h2c} sequence with {@code Connection} and {@code HTTP2-Settings};</li>
 *   <li>returns {@value #BRANCH_H1} for all other cleartext HTTP traffic.</li>
 * </ul>
 * <p>
 * Typical usage demo:
 * <pre>
 *   ProtoRoutingBuilder&lt;ByteBuf, ByteBuf&gt; routing = ProtoHelper.typedRoutingAsStatic(new HttpAggregatorRoute());
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -&gt; {
 *       branch.addLast("h2-codec", new Http2ServerDuplexe(4096, 8192, 1048576));
 *       branch.addLastDecoder("h2-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("h2-handler", new HttpDispatchHandler(false));
 *   });
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -&gt; {
 *       branch.addLast("h2c-upgrade-codec", new H2cUpgradeServerDuplexe(4096, 8192, 8192, 1048576));
 *       branch.addLastDecoder("h2c-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("h2c-handler", new HttpDispatchHandler(false));
 *   });
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -&gt; {
 *       branch.addLast("http-codec", new HttpServerDuplexe(4096, 8192, 8192));
 *       branch.addLastDecoder("http-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("http-handler", new HttpDispatchHandler(false));
 *   });
 *   ctx.addLast("http-detect", routing.build());
 * </pre>
 * <p>
 * Use this selector only for cleartext TCP HTTP entry points. For TLS + ALPN based routing,
 * use {@link Http2OverTlsRoute}.
 */
public class HttpAggregatorRoute implements ProtoRoutingDataSelector<ByteBuf, ByteBuf>, HttpRouteKey {
    private static final Logger logger           = Logger.getLogger(HttpAggregatorRoute.class);
    /** Minimum bytes required for protocol detection. */
    private static final int    MIN_DETECT_BYTES = 4;

    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
        boolean printLog = context.getConfig().isPrintLog();
        // h2c detection requires data — defer if queue is empty (onActive phase)
        if (rcvUp.queueSize() == 0) {
            return null;
        }

        ByteBuf first = rcvUp.peekMessage();
        if (first == null) {
            return null; // wait for more data
        }

        ByteBuf inspectBuf = ByteBufUtils.queueBuffer(rcvUp);
        if (inspectBuf.readableBytes() < MIN_DETECT_BYTES) {
            return null;
        }
        int b0 = inspectBuf.getByte(0) & 0xFF;

        // "PRI " = 0x50 0x52 0x49 0x20 → HTTP/2 Prior Knowledge (RFC 9113 §3.4)
        String branch;
        if (b0 == 0x50                                      // 'P'
                && (inspectBuf.getByte(1) & 0xFF) == 0x52 // 'R'
                && (inspectBuf.getByte(2) & 0xFF) == 0x49 // 'I'
                && (inspectBuf.getByte(3) & 0xFF) == 0x20 // ' '
        ) {
            branch = BRANCH_H2; // http/2
        } else if (shouldWaitForHttpHeaders(inspectBuf)) {
            return null;
        } else if (isH2cUpgrade(inspectBuf)) {
            branch = BRANCH_H2C;
        } else {
            branch = BRANCH_H1; // http/1.1
        }

        if (printLog) {
            logger.info("[H2C-DETECT] channel=" + context.getChannel().getChannelId() + " -> branch='" + branch + "'");
        }

        return branch;
    }

    private boolean isH2cUpgrade(ByteBuf inspectBuf) {
        if (!looksLikeHttpRequest(inspectBuf)) {
            return false;
        }

        int headerEnd = findHeaderEnd(inspectBuf);
        if (headerEnd < 0) {
            return false;
        }

        String requestHead = inspectBuf.getString(0, headerEnd, StandardCharsets.US_ASCII);
        String[] lines = requestHead.split("\\r\\n");
        if (lines.length == 0) {
            return false;
        }
        if (!lines[0].toUpperCase(Locale.ROOT).endsWith("HTTP/1.1")) {
            return false;
        }

        String upgrade = null;
        String connection = null;
        int settingsCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            if (HttpHeaderNames.UPGRADE.equals(name)) {
                upgrade = value;
            } else if (HttpHeaderNames.CONNECTION.equals(name)) {
                connection = value;
            } else if (HttpHeaderNames.HTTP2_SETTINGS.equals(name)) {
                settingsCount++;
            }
        }

        if (!HttpHeaderValues.H2C.equalsIgnoreCase(upgrade)) {
            return false;
        }
        if (settingsCount != 1) {
            return false;
        }
        return containsConnectionToken(connection, HttpHeaderValues.UPGRADE) && containsConnectionToken(connection, HttpHeaderNames.HTTP2_SETTINGS);
    }

    private boolean shouldWaitForHttpHeaders(ByteBuf inspectBuf) {
        return looksLikeHttpRequest(inspectBuf) && findHeaderEnd(inspectBuf) < 0;
    }

    private boolean looksLikeHttpRequest(ByteBuf inspectBuf) {
        int b0 = inspectBuf.getByte(0) & 0xFF;
        return b0 >= 0x41 && b0 <= 0x5A;
    }

    private int findHeaderEnd(ByteBuf inspectBuf) {
        int readable = inspectBuf.readableBytes();
        for (int i = 0; i <= readable - 4; i++) {
            if (inspectBuf.getByte(i) == '\r' && inspectBuf.getByte(i + 1) == '\n' && inspectBuf.getByte(i + 2) == '\r' && inspectBuf.getByte(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private boolean containsConnectionToken(String headerValue, String token) {
        if (headerValue == null || token == null) {
            return false;
        }
        String[] tokens = headerValue.split(",");
        for (String item : tokens) {
            if (token.equalsIgnoreCase(item.trim())) {
                return true;
            }
        }
        return false;
    }
}