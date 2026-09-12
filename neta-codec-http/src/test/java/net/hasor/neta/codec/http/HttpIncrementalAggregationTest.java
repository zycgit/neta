/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpIncrementalAggregationTest extends AbstractHttpTest {
    @Test
    public void requestConsumesPartsBeforeEndWithoutPublishing() throws Throwable {
        assertIncrementalOwnership(false);
    }

    @Test
    public void responseConsumesPartsBeforeEndWithoutPublishing() throws Throwable {
        assertIncrementalOwnership(true);
    }

    private void assertIncrementalOwnership(boolean response) throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, response, 4096)) {
                CountingHeaders first = new CountingHeaders();
                first.addHeader("X-First", "one");
                CountingHeaders second = new CountingHeaders();
                second.addHeader("X-Second", "two");
                ByteBuf body = ascii("body");
                CountingContent part = new CountingContent(body);
                h.feed(h.start(), first, second, new DefaultLastHttpHeaders(), part);
                assertFalse(h.dst.hasMore());
                assertFalse(h.src.hasMore());
                assertTrue(h.src.queueNames().isEmpty());
                assertEquals(0, first.releases);
                assertEquals(1, second.releases);
                assertEquals(1, part.releases);
                assertNull(part.content());
                assertEquals(1, body.refCnt());
                h.feed(new DefaultLastHttpContent(ByteBuf.EMPTY));
                HttpObject full = h.dst.takeMessage();
                try {
                    assertSame(body, ((HttpContent) full).content());
                    assertEquals("one", ((HttpHeaders) full).getString("X-First"));
                    assertEquals("two", ((HttpHeaders) full).getString("X-Second"));
                    assertEquals("4", ((HttpHeaders) full).getString(HttpHeaderNames.CONTENT_LENGTH));
                } finally {
                    full.release();
                }
                assertEquals(1, first.releases);
                assertEquals(0, body.refCnt());
            }
        });
    }

    @Test
    public void requestWaitsForOutputSlotWithoutLosingTerminalPart() throws Throwable {
        assertBackpressure(false);
    }

    @Test
    public void responseWaitsForOutputSlotWithoutLosingTerminalPart() throws Throwable {
        assertBackpressure(true);
    }

    private void assertBackpressure(boolean response) throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, response, 4096)) {
                HttpObject occupied = h.start();
                assertTrue(h.dst.offerMessage(occupied));
                ByteBuf body = ascii("last");
                DefaultLastHttpContent last = new DefaultLastHttpContent(body);
                assertEquals(ProtoStatus.Stop, h.feed(h.start(), new DefaultLastHttpHeaders(), last));
                assertSame(last, h.src.peekMessage());
                assertSame(occupied, h.dst.takeMessage());
                occupied.release();
                assertEquals(ProtoStatus.Next, h.run());
                assertFalse(h.src.hasMore());
                assertEquals(1, h.dst.queueSize());
                HttpObject full = h.dst.takeMessage();
                assertSame(body, ((HttpContent) full).content());
                full.release();
                assertEquals(0, body.refCnt());
                h.run();
                assertFalse(h.dst.hasMore());
            }
        });
    }

    @Test
    public void closeReleasesPartiallyAggregatedRequestAndResponse() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                ByteBuf body = ascii("unfinished");
                CountingHeaders headers = new CountingHeaders();
                try (Harness h = new Harness(neta, response, 4096)) {
                    h.feed(h.start(), headers, new DefaultHttpContent(body));
                    assertEquals(1, body.refCnt());
                }
                assertEquals(0, body.refCnt());
                assertEquals(1, headers.releases);
            });
        }
    }

    @Test
    public void transparentEventReleasesPartialStateAndResumes() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                ByteBuf body = ascii("unfinished");
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(body));
                h.pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
                assertEquals(0, body.refCnt());
                DefaultHttpByteBuf raw = new DefaultHttpByteBuf(ascii("raw"));
                h.feed(raw);
                assertSame(raw, h.dst.takeMessage());
                raw.release();
                h.pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage() instanceof FullHttpRequest);
            }
        });
    }

    @Test
    public void splitHeadersTriggerContinueBeforeBodyExactlyOnce() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 16)) {
                h.feed(h.start(), headers(HttpHeaderNames.EXPECT, "100-continue", HttpHeaderNames.CONTENT_LENGTH, "4"));
                assertTrue(h.pipe.channelOutbound().isEmpty());
                h.feed(new DefaultLastHttpHeaders());
                List<HttpObject> replies = receiveAndOutBound(h.pipe);
                assertEquals(1, replies.size());
                assertEquals(HttpStatus.CONTINUE, ((HttpResponse) replies.get(0)).status());
                free(replies);
                h.feed(new DefaultLastHttpContent(ascii("body")));
                assertTrue(h.dst.peekMessage() instanceof FullHttpRequest);
                assertTrue(h.pipe.channelOutbound().isEmpty());
            }
        });
    }

    @Test
    public void oversizedSplitHeadersAreRejectedBeforeBodyAndNextRequestRecovers() throws Throwable {
        assertRejectedHead(HttpHeaderNames.CONTENT_LENGTH, "17", HttpStatus.REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    public void unsupportedSplitExpectationIsRejectedBeforeBody() throws Throwable {
        assertRejectedHead(HttpHeaderNames.EXPECT, "unsupported", HttpStatus.EXPECTATION_FAILED);
    }

    private void assertRejectedHead(String name, String value, HttpStatus status) throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 16)) {
                h.feed(h.start(), headers(name, value), new DefaultLastHttpHeaders());
                List<HttpObject> replies = receiveAndOutBound(h.pipe);
                assertEquals(1, replies.size());
                assertEquals(status, ((HttpResponse) replies.get(0)).status());
                free(replies);
                ByteBuf rejected = ascii("ignored");
                h.feed(new DefaultLastHttpContent(rejected));
                assertFalse(h.dst.hasMore());
                assertEquals(0, rejected.refCnt());
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertEquals(1, h.dst.queueSize());
            }
        });
    }

    @Test
    public void oversizedTerminalBodyReleasesAllPartsAndRecovers() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4)) {
                ByteBuf first = ascii("1234");
                ByteBuf last = ascii("5");
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(first));
                h.feed(new DefaultLastHttpContent(last));
                assertEquals(0, first.refCnt());
                assertEquals(0, last.refCnt());
                assertFalse(h.dst.hasMore());
                List<HttpObject> replies = receiveAndOutBound(h.pipe);
                assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, ((HttpResponse) replies.get(0)).status());
                free(replies);
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage() instanceof FullHttpRequest);
            }
        });
    }

    @Test
    public void responseLimitFailureReleasesPartialState() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, true, 4)) {
                ByteBuf first = ascii("1234");
                ByteBuf last = ascii("5");
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(first));
                try {
                    h.feed(new DefaultLastHttpContent(last));
                    fail("expected size limit failure");
                } catch (HttpContentTooLargeException expected) {
                    assertEquals(0, first.refCnt());
                    assertEquals(0, last.refCnt());
                }
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage() instanceof FullHttpResponse);
            }
        });
    }

    @Test
    public void largeSingleBodyRetainsOriginalBufferForBothRoles() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    ByteBuf body = ByteBuf.wrap(new byte[2048]);
                    DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                    headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "2048");
                    h.feed(h.start(), headers, new DefaultLastHttpContent(body));
                    assertSame(body, ((HttpContent) h.dst.peekMessage()).content());
                    h.dst.takeMessage().release();
                    assertEquals(0, body.refCnt());
                }
            });
        }
    }

    @Test
    public void upstreamErrorReleasesPartialMessageWithoutClearingException() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                ByteBuf body = ascii("partial");
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(body));
                h.aggregator.onError(h.context, new IllegalStateException("upstream failed"), () -> fail("must propagate error"));
                assertEquals(0, body.refCnt());
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage() instanceof FullHttpRequest);
            }
        });
    }

    @Test
    public void failedHeaderMergeReleasesCurrentAndPreviouslyOwnedParts() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                CountingHeaders first = new CountingHeaders();
                CountingHeaders broken = new CountingHeaders() {
                    @Override
                    public int headerSize() {
                        throw new IllegalStateException("header access failed");
                    }
                };
                ByteBuf body = ascii("partial");
                h.feed(h.start(), first, new DefaultHttpContent(body));
                try {
                    h.feed(broken);
                    fail("expected failed header access");
                } catch (IllegalStateException expected) {
                    assertEquals("header access failed", expected.getMessage());
                }
                assertEquals(1, first.releases);
                assertEquals(1, broken.releases);
                assertEquals(0, body.refCnt());
            }
        });
    }

    @Test
    public void emptyBadHeaderBlockPreservesFailureMetadata() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                HttpHeaders bad = new DefaultLastHttpHeaders().markBad("invalid header");
                h.feed(h.start(), headers("X-First", "one"), bad, new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage().isBad());
                assertEquals("invalid header", h.dst.peekMessage().badReason());
            }
        });
    }

    @Test
    public void customRequestLineTransfersOwnershipToFullMessage() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                CustomRequest request = new CustomRequest();
                request.streamId(42).markBad("custom metadata");
                h.feed(request, new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertEquals(0, request.releases);
                FullHttpRequest full = (FullHttpRequest) h.dst.takeMessage();
                try {
                    assertEquals("/custom", full.uri());
                    assertEquals(42, full.streamId());
                    assertEquals("custom metadata", full.badReason());
                } finally {
                    full.release();
                }
                assertEquals(1, request.releases);
            }
        });
    }

    @Test
    public void finalLengthPreservesEquivalentTextAndNormalizesInvalidValues() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    for (String value : new String[] { null, "4", "004", "+004", " 4 ", "2", "-1", "invalid", "9223372036854775808" }) {
                        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                        if (value != null) {
                            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, value);
                        }
                        h.feed(h.start(), headers, new DefaultLastHttpContent(ascii("body")));
                        HttpObject full = h.dst.takeMessage();
                        try {
                            String expected = "4".equals(value) || "004".equals(value) || " 4 ".equals(value) ? value : "4";
                            assertEquals(expected, ((HttpHeaders) full).getString(HttpHeaderNames.CONTENT_LENGTH));
                            assertEquals(4, ((HttpContent) full).content().readableBytes());
                        } finally {
                            full.release();
                        }
                    }
                }
            });
        }
    }

    @Test
    public void finalizationKeepsFirstDuplicateHeaderSemanticsInEitherOrder() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    for (boolean encodingFirst : new boolean[] { false, true }) {
                        for (boolean chunkedFirst : new boolean[] { false, true }) {
                            DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                            if (!encodingFirst) {
                                headers.addHeader("cOnTeNt-LeNgTh", "004");
                            }
                            headers.addHeader("tRaNsFeR-EnCoDiNg", chunkedFirst ? "gzip, ChUnKeD" : "gzip");
                            if (encodingFirst) {
                                headers.addHeader("cOnTeNt-LeNgTh", "004");
                            }
                            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "99");
                            headers.addHeader(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
                            h.feed(h.start(), headers, new DefaultLastHttpContent(ascii("body")));
                            HttpObject full = h.dst.takeMessage();
                            try {
                                HttpHeaders result = (HttpHeaders) full;
                                assertEquals(chunkedFirst ? List.of("4") : List.of("004", "99"), result.getValues(HttpHeaderNames.CONTENT_LENGTH));
                                assertEquals(chunkedFirst ? List.of() : List.of("gzip", "chunked"), result.getValues(HttpHeaderNames.TRANSFER_ENCODING));
                            } finally {
                                full.release();
                            }
                        }
                    }
                }
            });
        }
    }

    @Test
    public void finalizationSeesHeaderReplacementByHeadersClosedHook() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    h.afterHeadersClosed = headers -> {
                        headers.setHeader(HttpHeaderNames.CONTENT_LENGTH, "999");
                        headers.setHeader("X-Hook", "changed");
                    };
                    h.feed(h.start(), headers(HttpHeaderNames.CONTENT_LENGTH, "4"), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ascii("body")));
                    HttpHeaders result = (HttpHeaders) h.dst.peekMessage();
                    assertEquals("4", result.getString(HttpHeaderNames.CONTENT_LENGTH));
                    assertEquals("changed", result.getString("X-Hook"));
                }
            });
        }
    }

    @Test
    public void finalizationSeesMutableHeaderValuesChangedByHook() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    StringBuilder length = new StringBuilder("4");
                    StringBuilder encoding = new StringBuilder("gzip");
                    DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                    headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, length);
                    headers.addHeader(HttpHeaderNames.TRANSFER_ENCODING, encoding);
                    h.afterHeadersClosed = ignored -> {
                        length.replace(0, length.length(), "999");
                        encoding.append(", chunked");
                    };
                    h.feed(h.start(), headers, new DefaultLastHttpContent(ascii("body")));
                    HttpHeaders result = (HttpHeaders) h.dst.peekMessage();
                    assertEquals("4", result.getString(HttpHeaderNames.CONTENT_LENGTH));
                    assertFalse(result.containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
                }
            });
        }
    }

    @Test
    public void finalizationSeesHeadersAddedAfterInitialHeaderBlock() throws Throwable {
        for (boolean response : new boolean[] { false, true }) {
            autoCloseNeta(neta -> {
                try (Harness h = new Harness(neta, response, 4096)) {
                    DefaultTrailerHttpHeaders trailer = new DefaultTrailerHttpHeaders();
                    trailer.addHeader(HttpHeaderNames.CONTENT_LENGTH, "999");
                    trailer.addHeader(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
                    trailer.addHeader("X-Trail", "kept");
                    h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(ascii("bo")), trailer, new DefaultLastHttpContent(ascii("dy")));
                    HttpHeaders result = (HttpHeaders) h.dst.peekMessage();
                    assertEquals("4", result.getString(HttpHeaderNames.CONTENT_LENGTH));
                    assertNull(result.getString(HttpHeaderNames.TRANSFER_ENCODING));
                    assertEquals("kept", result.getString("X-Trail"));
                }
            });
        }
    }

    @Test
    public void finalizationWithoutTerminalHeadersStillNormalizesLength() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                h.feed(h.start(), headers("X-Only", "value"), new DefaultLastHttpContent(ascii("body")));
                assertEquals("4", ((HttpHeaders) h.dst.peekMessage()).getString(HttpHeaderNames.CONTENT_LENGTH));
            }
        });
    }

    @Test
    public void resumedFinalizationReadsHookRetainedHeadersAgain() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                HttpHeaders[] retained = new HttpHeaders[1];
                h.afterHeadersClosed = headers -> retained[0] = headers;
                HttpObject occupied = h.start();
                assertTrue(h.dst.offerMessage(occupied));
                h.feed(h.start(), headers(HttpHeaderNames.CONTENT_LENGTH, "4"), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ascii("body")));
                retained[0].setHeader(HttpHeaderNames.CONTENT_LENGTH, "999");
                h.dst.takeMessage().release();
                assertEquals(ProtoStatus.Next, h.run());
                assertEquals("4", ((HttpHeaders) h.dst.peekMessage()).getString(HttpHeaderNames.CONTENT_LENGTH));
            }
        });
    }

    @Test
    public void rawEncodingEntryRemainsReadableAndReleasesItsSource() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                ByteBuf source = ascii("Transfer-Encoding:gzip");
                DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 17, 18, 4);
                DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                headers.addHeaderEntry(entry);
                h.feed(h.start(), headers, new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertEquals("Transfer-Encoding", entry.getName());
                assertEquals("gzip", ((HttpHeaders) h.dst.peekMessage()).getString(HttpHeaderNames.TRANSFER_ENCODING));
                h.dst.takeMessage().release();
                assertTrue(source.isFree());
            }
        });
    }

    @Test
    public void emptyPartsBeforeAndAfterFirstBodyKeepOriginalBuffer() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                ByteBuf firstEmpty = ByteBuf.wrap(new byte[0]);
                ByteBuf lastEmpty = ByteBuf.wrap(new byte[0]);
                ByteBuf body = ascii("body");
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultHttpContent(firstEmpty), new DefaultHttpByteBuf(body), new DefaultLastHttpContent(lastEmpty));
                assertTrue(firstEmpty.isFree());
                assertTrue(lastEmpty.isFree());
                assertSame(body, ((HttpContent) h.dst.peekMessage()).content());
                h.dst.takeMessage().release();
                assertTrue(body.isFree());
            }
        });
    }

    @Test
    public void expectationKeepsFirstValueAndClosePrecedence() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 16)) {
                for (String first : new String[] { "", "100-CoNtInUe", "unsupported" }) {
                    DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
                    headers.addHeader(HttpHeaderNames.EXPECT, first);
                    headers.addHeader(HttpHeaderNames.EXPECT, "unsupported");
                    headers.addHeader(HttpHeaderNames.CONNECTION, "keep-alive, CLOSE");
                    headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");
                    h.feed(h.start(), headers);
                    List<HttpObject> replies = receiveAndOutBound(h.pipe);
                    try {
                        assertEquals(first.isEmpty() ? 0 : 1, replies.size());
                        if (!first.isEmpty()) {
                            assertEquals(first.equals("unsupported") ? HttpStatus.EXPECTATION_FAILED : HttpStatus.CONTINUE, ((HttpResponse) replies.get(0)).status());
                            assertEquals(first.equals("unsupported") ? HttpHeaderValues.CLOSE : null, ((HttpHeaders) replies.get(0)).getString(HttpHeaderNames.CONNECTION));
                        }
                    } finally {
                        free(replies);
                    }
                    h.feed(new DefaultLastHttpContent(ascii("body")));
                    if (h.dst.hasMore()) {
                        h.dst.takeMessage().release();
                    }
                }
            }
        });
    }

    @Test
    public void oversizedContinueRequestSendsOnly413() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4)) {
                h.feed(h.start(), headers(HttpHeaderNames.EXPECT, "100-continue", HttpHeaderNames.CONTENT_LENGTH, "5"), new DefaultLastHttpHeaders());
                List<HttpObject> replies = receiveAndOutBound(h.pipe);
                try {
                    assertEquals(1, replies.size());
                    assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, ((HttpResponse) replies.get(0)).status());
                } finally {
                    free(replies);
                }
                h.feed(new DefaultLastHttpContent(ascii("12345")));
                assertFalse(h.dst.hasMore());
            }
        });
    }

    @Test
    public void headerHookFailureReleasesOwnedPartsAndResetsState() throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, false, 4096)) {
                CountingHeaders headers = new CountingHeaders();
                h.afterHeadersClosed = ignored -> { throw new IllegalStateException("hook failed"); };
                try {
                    h.feed(h.start(), headers, new DefaultLastHttpHeaders());
                    fail("expected hook failure");
                } catch (IllegalStateException expected) {
                    assertEquals("hook failed", expected.getMessage());
                    assertEquals(1, headers.releases);
                }
                h.afterHeadersClosed = ignored -> {};
                h.feed(h.start(), new DefaultLastHttpHeaders(), new DefaultLastHttpContent(ByteBuf.EMPTY));
                assertTrue(h.dst.peekMessage() instanceof FullHttpRequest);
            }
        });
    }

    @Test
    public void requestFinalizationUsesCustomHeaderStorage() throws Throwable {
        assertCustomHeaderStorage(false);
    }

    @Test
    public void responseFinalizationUsesCustomHeaderStorage() throws Throwable {
        assertCustomHeaderStorage(true);
    }

    private void assertCustomHeaderStorage(boolean response) throws Throwable {
        autoCloseNeta(neta -> {
            try (Harness h = new Harness(neta, response, 4096)) {
                for (String encoding : new String[] { "gzip", "chunked" }) {
                    ForwardingHeaders headers = new ForwardingHeaders();
                    headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "004");
                    headers.addHeader(HttpHeaderNames.TRANSFER_ENCODING, encoding);
                    h.feed(h.start(), headers, new DefaultLastHttpContent(ascii("body")));
                    HttpObject full = h.dst.takeMessage();
                    try {
                        HttpHeaders result = (HttpHeaders) full;
                        assertEquals(encoding.equals("chunked") ? "4" : "004", result.getString(HttpHeaderNames.CONTENT_LENGTH));
                        assertEquals(encoding.equals("chunked") ? null : "gzip", result.getString(HttpHeaderNames.TRANSFER_ENCODING));
                        assertEquals(4, ((HttpContent) full).content().readableBytes());
                    } finally {
                        full.release();
                    }
                    assertEquals(0, headers.headerSize());
                }
            }
        });
    }

    private static class ForwardingHeaders extends DefaultLastHttpHeaders {
        private final DefaultHttpHeaders delegate = new DefaultHttpHeaders();

        @Override
        public DefaultHttpHeaders addHeader(String name, String value) { this.delegate.addHeader(name, value); return this; }

        @Override
        public DefaultHttpHeaders setHeader(String name, String value) { this.delegate.setHeader(name, value); return this; }

        @Override
        public DefaultHttpHeaders removeHeader(String name) { this.delegate.removeHeader(name); return this; }

        @Override
        public DefaultHttpHeaders clearHeader() { this.delegate.clearHeader(); return this; }

        @Override
        public DefaultHttpHeaders appendHeaders(HttpHeaders headers) { this.delegate.appendHeaders(headers); return this; }

        @Override
        public String getString(String name) { return this.delegate.getString(name); }

        @Override
        public long getLong(String name, long fallback) { return this.delegate.getLong(name, fallback); }

        @Override
        public int getInt(String name, int fallback) { return this.delegate.getInt(name, fallback); }

        @Override
        public List<String> getValues(String name) { return this.delegate.getValues(name); }

        @Override
        public Set<String> headerNames() { return this.delegate.headerNames(); }

        @Override
        public boolean containsHeader(String name) { return this.delegate.containsHeader(name); }

        @Override
        public int headerSize() { return this.delegate.headerSize(); }

        @Override
        public void release() { this.delegate.release(); super.release(); }
    }

    private static class CustomRequest extends AbstractHttpObject<HttpRequest> implements HttpRequest {
        private final DefaultHttpRequest delegate = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/custom");
        private int releases;

        @Override
        protected HttpRequest self() { return this; }

        @Override
        public HttpVersion protocolVersion() { return this.delegate.protocolVersion(); }

        @Override
        public HttpRequest protocolVersion(HttpVersion version) { this.delegate.protocolVersion(version); return this; }

        @Override
        public HttpMethod method() { return this.delegate.method(); }

        @Override
        public HttpRequest method(HttpMethod method) { this.delegate.method(method); return this; }

        @Override
        public String uri() { return this.delegate.uri(); }

        @Override
        public HttpRequest uri(String uri) { this.delegate.uri(uri); return this; }

        @Override
        public void release() { this.releases++; this.delegate.release(); this.resetHttpObjectState(); }
    }

    private class Harness implements AutoCloseable {
        private final boolean response;
        private final AbstractHttpAggregator<?> aggregator;
        private final ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        private final ProtoQueue<HttpObject> dst = new ProtoQueue<>(1);
        private final VirtualPipe pipe;
        private ProtoContext context;
        private Consumer<HttpHeaders> afterHeadersClosed = headers -> {};

        private Harness(NetManager neta, boolean response, int limit) throws Throwable {
            this.response = response;
            this.aggregator = response ? new HttpResponseAggregator(limit) {
                @Override
                public void onInit(String name, int poolSize, ProtoContext ctx) {
                    super.onInit(name, poolSize, ctx);
                    context = ctx;
                }

                @Override
                protected void onHeadersClosed(ProtoContext ctx, HttpResponse message, HttpHeaders headers, long contentLength) {
                    super.onHeadersClosed(ctx, message, headers, contentLength);
                    afterHeadersClosed.accept(headers);
                }
            } : new HttpRequestAggregator(limit) {
                @Override
                public void onInit(String name, int poolSize, ProtoContext ctx) {
                    super.onInit(name, poolSize, ctx);
                    context = ctx;
                }

                @Override
                protected void onHeadersClosed(ProtoContext ctx, HttpRequest message, HttpHeaders headers, long contentLength) {
                    super.onHeadersClosed(ctx, message, headers, contentLength);
                    afterHeadersClosed.accept(headers);
                }
            };
            this.pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("aggregate", this.aggregator), response ? VrtSoConfig.asClient() : VrtSoConfig.asServer());
            assertNotNull(this.context);
        }

        private HttpObject start() {
            return this.response ? new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK) : new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/incremental");
        }

        private ProtoStatus feed(HttpObject... parts) throws Throwable {
            assertTrue(this.src.offerMessage(parts));
            return this.run();
        }

        private ProtoStatus run() throws Throwable {
            return this.aggregator.onMessage(this.context, this.src, this.dst);
        }

        @Override
        public void close() {
            this.aggregator.onClose(this.context);
            this.src.clearAndRelease();
            this.dst.clearAndRelease();
            free(receiveAndOutBound(this.pipe));
        }
    }

    private static class CountingHeaders extends DefaultHttpHeaders {
        private int releases;

        @Override
        public void release() {
            this.releases++;
            super.release();
        }
    }

    private static class CountingContent extends DefaultHttpContent {
        private int releases;

        private CountingContent(ByteBuf content) {
            super(content);
        }

        @Override
        public void release() {
            this.releases++;
            super.release();
        }
    }
}
