/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpHeaderFramingTest extends AbstractHttpTest {
    @Test
    public void collidingOrdinaryHeadersRemainOrdinaryAcrossPacketBoundaries() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            autoCloseNeta(neta -> {
                VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                    if (request) {
                        ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                    } else {
                        ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                    }
                }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());

                String initial = request ? "POST / HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n";
                String fields = "Content-Lengtx: invalid\r\nTransfer-Encodinx: chunked\r\nConnectiox: close\r\ncOnTeNt-LeNgTh: 3\r\ncOnNeCtIoN: keep-alive\r\n\r\nabc";
                String wire = initial + fields;
                for (int split = 0; split <= wire.length(); split++) {
                    List<HttpObject> out = new ArrayList<>();
                    try {
                        out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(0, split))));
                        out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(split))));
                        assertTrue(pipe.channelInboundErrors().isEmpty());
                        StringBuilder body = new StringBuilder();
                        int last = 0;
                        int ordinary = 0;
                        for (HttpObject item : out) {
                            if (item instanceof HttpHeaders headers && headers.containsHeader("Content-Lengtx")) {
                                assertEquals("invalid", headers.getString("Content-Lengtx"));
                                ordinary++;
                            }
                            if (item instanceof HttpContent content) {
                                ByteBuf bytes = content.content();
                                body.append(bytes.getString(0, bytes.readableBytes(), StandardCharsets.US_ASCII));
                            }
                            if (item instanceof LastHttpContent) {
                                last++;
                            }
                        }

                        assertEquals("abc", body.toString());
                        assertEquals(1, ordinary);
                        assertEquals(1, last);
                    } finally {
                        free(out);
                    }
                }
            });
        }
    }

    @Test
    public void invalidKnownValuesDoNotFallBackToOrdinaryHeaders() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            autoCloseNeta(neta -> {
                VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                    if (request) {
                        ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                    } else {
                        ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                    }
                }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
                String initial = request ? "POST / HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n";
                List<HttpObject> out = receiveAndIntBound(pipe, ascii(initial + "cOnTeNt-LeNgTh: invalid\r\n\r\n"));
                try {
                    assertFalse(pipe.channelInboundErrors().isEmpty());
                } finally {
                    free(out);
                }
            });
        }
    }

}
