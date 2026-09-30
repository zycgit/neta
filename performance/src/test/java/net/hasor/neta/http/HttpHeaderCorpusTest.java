/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpHeaderCorpusTest {
    @Test
    public void bothDecodersPreserveEveryCorpusMessage() throws Throwable {
        for (String profile : new String[] { "ordinary", "sameLength", "mixedCasePost", "fragmentedChunked", "response" }) {
            HttpHeaderCorpus corpus = new HttpHeaderCorpus(profile);
            NetManager neta = new NetManager();
            EmbeddedChannel netty = new EmbeddedChannel(corpus.response ? new io.netty.handler.codec.http.HttpResponseDecoder() : new io.netty.handler.codec.http.HttpRequestDecoder());
            LeakMetricSnapshot before = LeakMetricSnapshot.capture(ByteBufAllocator.DEFAULT.metric());
            try {
                VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(175), ctx -> {
                    ctx.addLastDecoder("decoder", corpus.response ? new HttpResponseDecoder() : new HttpRequestDecoder());
                }, corpus.response ? VrtSoConfig.asClient() : VrtSoConfig.asServer());
                // Reuse both channels through two rotations to catch incomplete framing/state reset.
                for (int repeat = 0; repeat < 2; repeat++) {
                    for (HttpHeaderCorpus.Message message : corpus.messages) {
                        Map<String, String> netaHeaders = new LinkedHashMap<>();
                        Map<String, String> nettyHeaders = new LinkedHashMap<>();
                        StringBuilder netaBody = new StringBuilder();
                        StringBuilder nettyBody = new StringBuilder();
                        int netaLast = 0, nettyLast = 0;
                        for (byte[] packet : message.packets) {
                            Object[] out = channel.receiveDataAndReturning(ByteBuf.wrap(packet));
                            try {
                                for (Object item : out) {
                                    assertTrue(item instanceof HttpObject);
                                    if (item instanceof HttpHeaders headers) {
                                        for (String name : headers.headerNames()) {
                                            assertNull(netaHeaders.put(name.toLowerCase(Locale.ROOT), headers.getString(name)));
                                        }
                                    }
                                    if (item instanceof HttpContent content) {
                                        ByteBuf body = content.content();
                                        netaBody.append(body.getString(0, body.readableBytes(), StandardCharsets.US_ASCII));
                                    }
                                    if (item instanceof LastHttpContent) {
                                        netaLast++;
                                    }
                                }
                            } finally {
                                for (Object item : out) {
                                    if (item instanceof HttpObject object) {
                                        object.release();
                                    } else if (item instanceof ByteBuf bytes) {
                                        bytes.release();
                                    }
                                }
                            }
                            netty.writeInbound(Unpooled.wrappedBuffer(packet));
                            Object item;
                            while ((item = netty.readInbound()) != null) {
                                try {
                                    assertTrue(((io.netty.handler.codec.http.HttpObject) item).decoderResult().isSuccess());
                                    if (item instanceof io.netty.handler.codec.http.HttpMessage head) {
                                        for (Map.Entry<String, String> field : head.headers()) {
                                            assertNull(nettyHeaders.put(field.getKey().toLowerCase(Locale.ROOT), field.getValue()));
                                        }
                                    }
                                    if (item instanceof io.netty.handler.codec.http.HttpContent content) {
                                        nettyBody.append(content.content().toString(StandardCharsets.US_ASCII));
                                    }
                                    if (item instanceof io.netty.handler.codec.http.LastHttpContent) {
                                        nettyLast++;
                                    }
                                } finally {
                                    ReferenceCountUtil.release(item);
                                }
                            }
                        }
                        assertEquals(profile, 12, message.headers.size());
                        assertEquals(profile, message.headers, netaHeaders);
                        assertEquals(profile, message.headers, nettyHeaders);
                        assertEquals(profile, message.body, netaBody.toString());
                        assertEquals(profile, message.body, nettyBody.toString());
                        assertEquals(profile, 1, netaLast);
                        assertEquals(profile, 1, nettyLast);
                    }
                }
            } finally {
                netty.finishAndReleaseAll();
                neta.shutdown();
                before.assertRestored(ByteBufAllocator.DEFAULT.metric(), profile);
            }
        }
    }
}
