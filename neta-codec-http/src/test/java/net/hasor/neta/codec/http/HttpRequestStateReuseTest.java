/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpRequestStateReuseTest extends AbstractHttpTest {
    private static final String[] REQUESTS = { //
            "POST /fixed HTTP/1.1\r\nHost: first\r\nContent-Length: 3\r\n\r\nabc",//
            "POST /chunked HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nde\r\n1\r\nf\r\n0\r\nX-End: yes\r\n\r\n", //
            "GET /empty HTTP/1.0\r\nHost: last\r\n\r\n" };

    @Test
    public void pipelinedRequestsDoNotCarryBodyOrTrailerState() throws Throwable {
        verifySequence(false);
    }

    @Test
    public void fragmentedRequestsResetStateBeforeTheNextInitialLine() throws Throwable {
        verifySequence(true);
    }

    @Test
    public void partialNextInitialLinePreservesTheCleanHeaderState() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("request", new HttpRequestDecoder()), VrtSoConfig.asServer());
            List<HttpObject> held = new ArrayList<>();
            try {
                held.addAll(receiveAndIntBound(pipe, ascii(REQUESTS[1])));
                assertReset(pipe);
                assertTrue(receiveAndIntBound(pipe, ascii("PO")).isEmpty());
                List<HttpObject> start = receiveAndIntBound(pipe, ascii("ST /next HTTP/1.1\r\n"));
                held.addAll(start);
                assertEquals(1, start.size());
                assertEquals("/next", ((HttpRequest) start.get(0)).uri());
                HttpContext.RequestDecodeState state = pipe.channel().findProtoContext(HttpContext.class).req;
                assertEquals(HttpContext.DecodePhase.READ_HEADER, state.decoderPhase);
                assertFalse(state.chunked);
                assertEquals(-1, state.contentLength);
                assertEquals(0, state.headerBytes);
                assertEquals(0, state.bytesRead);
                assertFalse(state.currentHeadersTrailer);
                assertEquals(1, state.packetSequence);
                List<HttpObject> end = receiveAndIntBound(pipe, ascii("Content-Length: 2\r\n\r\nxy"));
                held.addAll(end);
                assertEquals(2, end.size());
                assertTrue(end.get(0) instanceof LastHttpHeaders);
                assertTrue(end.get(1) instanceof LastHttpContent);
                assertEquals("xy", ((HttpContent) end.get(1)).content().getString(0, 2, StandardCharsets.US_ASCII));
                assertTrue(receiveAndIntError(pipe).isEmpty());
                assertReset(pipe);
            } finally {
                free(held);
            }
        });
    }

    private void verifySequence(boolean fragmented) throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("request", new HttpRequestDecoder(128, 96, 2)), VrtSoConfig.asServer());
            List<HttpObject> held = new ArrayList<>();
            try {
                if (fragmented) {
                    for (String request : REQUESTS) {
                        for (int offset = 0; offset < request.length(); offset += 5) {
                            held.addAll(receiveAndIntBound(pipe, ascii(request.substring(offset, Math.min(request.length(), offset + 5)))));
                        }
                        assertReset(pipe);
                    }
                } else {
                    held.addAll(receiveAndIntBound(pipe, ascii(String.join("", REQUESTS))));
                }
                String[] uris = { "/fixed", "/chunked", "/empty" };
                String[] bodies = { "abc", "def", "" };
                int requests = 0, endings = 0, trailers = 0;
                StringBuilder body = new StringBuilder();
                for (HttpObject item : held) {
                    assertFalse(item.isBad());
                    if (item instanceof HttpRequest) {
                        assertEquals(endings, requests);
                        assertEquals(uris[requests++], ((HttpRequest) item).uri());
                    }
                    if (item instanceof TrailerHttpHeaders) {
                        trailers++;
                        assertEquals("yes", ((HttpHeaders) item).getString("X-End"));
                    }
                    if (item instanceof HttpContent) {
                        HttpContent content = (HttpContent) item;
                        body.append(content.content().getString(0, content.content().readableBytes(), StandardCharsets.US_ASCII));
                    }
                    if (item instanceof LastHttpContent) {
                        assertEquals(bodies[endings++], body.toString());
                        body.setLength(0);
                    }
                }
                assertEquals(3, requests);
                assertEquals(3, endings);
                assertEquals(1, trailers);
                assertTrue(receiveAndIntError(pipe).isEmpty());
                assertReset(pipe);
            } finally {
                free(held);
            }
        });
    }

    private void assertReset(VirtualPipe pipe) {
        HttpContext.RequestDecodeState state = pipe.channel().findProtoContext(HttpContext.class).req;
        assertEquals(HttpContext.DecodePhase.READ_INITIAL, state.decoderPhase);
        assertFalse(state.chunked);
        assertEquals(-1, state.contentLength);
        assertEquals(0, state.bytesRead);
        assertEquals(0, state.headerBytes);
        assertEquals(0, state.packetSequence);
        assertFalse(state.currentHeadersTrailer);
        assertFalse(state.chunkSizeReady);
        assertFalse(state.chunkDelimiterReady);
        assertFalse(state.trailerComplete);
        assertFalse(state.emitEmptyEndContent);
        assertNull(state.currentMessage);
        assertNull(state.currentHeaders);
        assertNull(state.headerEntries);
        assertNull(state.headerLineScratch);
        assertNull(state.headerLineView);
    }
}
