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
package net.hasor.neta.codec.http2;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoRouting;
import net.hasor.neta.channel.ProtoSndQueue;

/**
 * Dedicated h2c (HTTP/2 cleartext) protocol routing implementation.
 * <p>
 * Detects HTTP/2 Prior Knowledge connections by inspecting the first 4 bytes
 * of the connection for the {@code "PRI "} prefix (0x50 0x52 0x49 0x20),
 * which is the start of the HTTP/2 connection preface (RFC 9113 §3.4).
 * <p>
 * Routing results:
 * <ul>
 *   <li>{@value #BRANCH_H2C} — HTTP/2 Prior Knowledge detected (PRI prefix)</li>
 *   <li>The configured default branch — any other data (typically HTTP/1.x)</li>
 * </ul>
 * <p>
 * When the h2c branch is selected, the {@code ProtoRoutingDuplexer} automatically
 * activates the selected branch by calling its {@code onActive}. This triggers
 * {@code Http2ServerDuplexe.onActive(sndDown)} which sends the server SETTINGS frame
 * as the connection preface. No user events are needed.
 * <p>
 * Usage with {@link net.hasor.neta.channel.ProtoRoutingDuplexer}:
 * <pre>
 *   H2cDetectRouting routing = new H2cDetectRouting("default");
 *   ProtoRoutingDuplexer.Builder&lt;ByteBuf&gt; builder = ProtoRoutingDuplexer.newBuilder(routing);
 *   builder.branch("h2c", h2cBranch -&gt; {
 *       h2cBranch.addLast("h2-codec", new Http2ServerDuplexe());
 *       h2cBranch.addLastDecoder("h2-aggregator", new HttpObjectAggregator(1048576));
 *   });
 *   builder.branch("default", httpBranch -&gt; {
 *       httpBranch.addLast("http-codec", new HttpServerDuplexe());
 *       httpBranch.addLastDecoder("http-aggregator", new HttpObjectAggregator(1048576));
 *   });
 *   ctx.addLast("protocol-detect", builder.build(ctx));
 * </pre>
 */
public class Http2RoutingForKnowledge implements ProtoRouting<ByteBuf> {
    /** Branch key for HTTP/2 cleartext (Prior Knowledge). */
    public static final String BRANCH_H2C = "h2c";

    /** Minimum bytes required for protocol detection. */
    private static final int MIN_DETECT_BYTES = 4;

    private final String defaultBranch;

    /**
     * Creates an h2c detection routing with the specified default branch.
     * @param defaultBranch branch key to use when the data does NOT match h2c (e.g. "default", "http")
     */
    public Http2RoutingForKnowledge(String defaultBranch) {
        if (defaultBranch == null || defaultBranch.isEmpty()) {
            throw new IllegalArgumentException("defaultBranch must not be null or empty.");
        }
        this.defaultBranch = defaultBranch;
    }

    @Override
    public String route(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Object> rcvDown) {
        // rcvUp is null during onActive phase — h2c detection requires data, so defer
        if (rcvUp == null) {
            return null;
        }

        ByteBuf first = rcvUp.peekMessage();
        if (first == null || first.readableBytes() < MIN_DETECT_BYTES) {
            return null; // wait for more data
        }

        int b0 = first.getByte(0) & 0xFF;

        // "PRI " = 0x50 0x52 0x49 0x20 → HTTP/2 Prior Knowledge (RFC 9113 §3.4)
        if (b0 == 0x50                                      // 'P'
                && (first.getByte(1) & 0xFF) == 0x52 // 'R'
                && (first.getByte(2) & 0xFF) == 0x49 // 'I'
                && (first.getByte(3) & 0xFF) == 0x20 // ' '
        ) {
            return BRANCH_H2C;
        }

        // Not h2c → use default branch
        return this.defaultBranch;
    }
}