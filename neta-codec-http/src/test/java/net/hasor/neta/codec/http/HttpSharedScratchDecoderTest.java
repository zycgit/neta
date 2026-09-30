/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpSharedScratchDecoderTest extends AbstractHttpTest {
    @Test
    public void requestHeadersAndTrailersSurviveTransfersAndNextRequest() throws Throwable {
        verifyDecodedOwnership(true);
    }

    @Test
    public void responseHeadersAndTrailersSurviveTransfersAndNextResponse() throws Throwable {
        verifyDecodedOwnership(false);
    }

    private void verifyDecodedOwnership(boolean request) throws Throwable {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, context -> {
                if (request) {
                    context.addLastDecoder("decoder", new HttpRequestDecoder());
                } else {
                    context.addLastDecoder("decoder", new HttpResponseDecoder());
                }
            }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
            String[] chunks = {//
                    request ? "POST /one HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n" + "X-One: fir",//
                    "st\r\nX-Two: sec", //
                    "ond\r\nTransfer-Encoding: chu",//
                    "nked\r\n\r\n", "1\r\na\r\n0\r\nX-Trailer: fir",//
                    "st\r\nX-Trailer-Two: sec", //
                    "ond\r\n\r\n" };
            int[] expectedBatches = { 1, 1, 1, 1, 1, 0, 2 };
            List<HttpObject> held = new ArrayList<>();
            List<HttpObject> next = new ArrayList<>();
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            DefaultHttpHeaders trailers = new DefaultHttpHeaders();
            try {
                for (int i = 0; i < chunks.length; i++) {
                    List<HttpObject> batch = receiveAndIntBound(pipe, ascii(chunks[i]));
                    held.addAll(batch);
                    assertEquals("batch " + i, expectedBatches[i], batch.size());
                    if (i == 1 || i == 2) {
                        assertTrue(batch.get(0) instanceof HttpHeaders);
                        assertFalse(batch.get(0) instanceof LastHttpHeaders);
                    }
                    if (i == 3) {
                        assertTrue(batch.get(0) instanceof LastHttpHeaders);
                        HttpContext context = pipe.channel().findProtoContext(HttpContext.class);
                        HttpContext.DecodeState<?> state = request ? context.req : context.resp;
                        assertNull(state.headerLineView);
                        assertNull(state.headerLineScratch);
                    }
                }
                assertTrue(held.get(held.size() - 2) instanceof TrailerHttpHeaders);
                assertTrue(held.get(held.size() - 1) instanceof LastHttpContent);
                for (HttpObject message : held) {
                    if (message instanceof HttpHeaders) {
                        (message instanceof TrailerHttpHeaders ? trailers : headers).appendOrTransferHeaders((HttpHeaders) message);
                    }
                }
                free(held);
                held.clear();

                String following = request ? "GET /two HTTP/1.1\r\n" : "HTTP/1.1 201 Created\r\n";
                next.addAll(receiveAndIntBound(pipe, ascii(following + "X-One: rep")));
                next.addAll(receiveAndIntBound(pipe, ascii("lacement\r\nContent-Length: 0\r\n\r\n")));
                assertEquals("first", headers.getString("X-One"));
                assertEquals("second", headers.getString("X-Two"));
                assertEquals("chunked", headers.getString(HttpHeaderNames.TRANSFER_ENCODING));
                assertEquals("first", trailers.getString("X-Trailer"));
                assertEquals("second", trailers.getString("X-Trailer-Two"));
            } finally {
                free(held);
                free(next);
                headers.release();
                trailers.release();
            }
        });
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }
}