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
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;

/**
 * Cleartext HTTP aggregate route selector.
 * <p>
 * This selector reads the received cleartext bytes before the flow enters a concrete HTTP codec branch and returns the branch key for HTTP/1.1, HTTP/2 prior knowledge, or h2c upgrade.
 * This selector can determine these three entry traffic types on the same TCP port.
 * <p>
 * Overall flow:
 * <pre>
 *   inbound TCP bytes
 *         v
 *   +------------------------+
 *   | HttpAggregatorRoute    |
 *   | inspect first bytes    |
 *   | inspect request head   |
 *   +------------------------+
 *      |          |          |
 *      |          |          +--> BRANCH_H1  --> HttpServerDuplexe --> HttpRequestAggregator --> handler
 *      |          |
 *      |          +-------------> BRANCH_H2C --> HttpServerDuplexe --> H2CUpgradeServerDuplexe --> handler
 *      |
 *      +------------------------> BRANCH_H2  --> Http2FrameDuplexe --> Http2ObjectDuplexe --> HttpServerDuplexeAggregator --> handler
 * </pre>
 * The upgrade request on the h2c path forms a one-time switching seed.
 * The following preface, SETTINGS, and SETTINGS ACK frames enter the switched HTTP/2 processing path.
 * <p>
 * Decision rules:
 * <ul>
 *   <li>returns {@code null} when the current bytes are still insufficient for a decision;</li>
 *   <li>returns {@value #BRANCH_H2} when the first four bytes are {@code "PRI "};</li>
 *   <li>returns {@value #BRANCH_H2C} when the complete HTTP/1.1 request head contains a valid {@code Upgrade: h2c}, the {@code Connection} header declares both {@code Upgrade} and {@code HTTP2-Settings}, and {@code HTTP2-Settings} appears exactly once;</li>
 *   <li>returns {@value #BRANCH_H1} for all other cleartext HTTP traffic.</li>
 * </ul>
 * <p>
 * Typical usage example:
 * <pre>
 *   ProtoRoutingBuilder&lt;ByteBuf, ByteBuf&gt; routing = ProtoHelper.typedRoutingAsStatic(new HttpAggregatorRoute());
 *   ProtoRoutingControl routingControl = routing.control();
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -&gt; {
 *       branch.addLast("h2-frame", new Http2FrameDuplexe(true));
 *       branch.addLast("h2-object", new Http2ObjectDuplexe(true, routingControl));
 *       branch.nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -&gt; partition.policy(new Http2ObjectPartitionPolicy()).byInitializer(partitionCtx -&gt; partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576))));
 *       branch.addLastDecoder("h2-handler", new HttpDispatchHandler(false));
 *   });
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -&gt; {
 *       branch.addLast("http-codec", new HttpServerDuplexe(4096, 8192, 8192));
 *       branch.addLast("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl));
 *   });
 *   routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -&gt; {
 *       branch.addLast("http-codec", new HttpServerDuplexe(4096, 8192, 8192));
 *       branch.addLastDecoder("http-aggregator", new HttpRequestAggregator(1048576));
 *       branch.addLastDecoder("http-handler", new HttpDispatchHandler(false));
 *   });
 *   ctx.addLast("http-detect", routing.build());
 * </pre>
 * <p>
 * This selector applies to cleartext TCP HTTP entries.
 * Use {@link HttpAggregatorOverTlsRoute} for aggregate routing on TLS entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public class HttpAggregatorRoute implements ProtoRoutingDataSelector<ByteBuf, ByteBuf>, HttpRouteKey {
    private static final Logger logger           = Logger.getLogger(HttpAggregatorRoute.class);
    /** Minimum number of bytes required for protocol detection. */
    private static final int    MIN_DETECT_BYTES = 4;

    /**
     * Returns the HTTP route branch key according to the currently received cleartext bytes.
     */
    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> sndDown) {
        boolean printLog = context.getConfig().isPrintLog();
        // h2c detection depends on incoming data; delay the decision when the queue is still empty, such as during onActive.
        if (rcvUp.queueSize() == 0) {
            return null;
        }

        ByteBuf first = rcvUp.peekMessage();
        if (first == null) {
            return null; // keep waiting for more data
        }

        ByteBuf inspectBuf = ByteBufUtils.queueBuffer(rcvUp);
        if (inspectBuf.readableBytes() < MIN_DETECT_BYTES) {
            return null;
        }
        int b0 = inspectBuf.getByte(0) & 0xFF;

        // "PRI " = 0x50 0x52 0x49 0x20, which identifies HTTP/2 prior knowledge (RFC 9113 §3.4).
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

    /**
     * Determines whether the current first packet is a valid h2c upgrade request according to the complete request head.
     */
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

    /**
     * Determines whether the current bytes already look like an HTTP/1.x request and are still waiting for a complete request head.
     */
    private boolean shouldWaitForHttpHeaders(ByteBuf inspectBuf) {
        return looksLikeHttpRequest(inspectBuf) && findHeaderEnd(inspectBuf) < 0;
    }

    /**
     * Determines whether the first byte matches the starting pattern of an uppercase HTTP method name.
     */
    private boolean looksLikeHttpRequest(ByteBuf inspectBuf) {
        int b0 = inspectBuf.getByte(0) & 0xFF;
        return b0 >= 0x41 && b0 <= 0x5A;
    }

    /**
     * Finds the end position of the HTTP request head and returns the next index after \r\n\r\n.
     */
    private int findHeaderEnd(ByteBuf inspectBuf) {
        int readable = inspectBuf.readableBytes();
        for (int i = 0; i <= readable - 4; i++) {
            if (inspectBuf.getByte(i) == '\r' && inspectBuf.getByte(i + 1) == '\n' && inspectBuf.getByte(i + 2) == '\r' && inspectBuf.getByte(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /**
     * Determines whether the Connection header contains the target token.
     */
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