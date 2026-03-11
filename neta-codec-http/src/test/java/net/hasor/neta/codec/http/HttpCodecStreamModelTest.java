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
package net.hasor.neta.codec.http;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.StringView;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.event.HttpThroughEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpCodecStreamModelTest {
    @Test
    public void testRequestDecoderSkipsLeadingEmptyLines() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("\r\n\r\nGET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof LastHttpContent);

        HttpRequest request = (HttpRequest) messages.get(0);
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/hello", request.uri());
        assertEquals("example.com", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.HOST));
        assertEquals(0, input.queueSize());
    }

    @Test
    public void testRequestDecoderDoesNotConsumeHalfInitialLine() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hel"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        assertEquals(1, input.queueSize());
        assertEquals("GET /hel", text(input.peekMessage()));
        assertEquals(0, output.queueSize());

        input.offerMessage(ascii("lo HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof LastHttpContent);

        HttpRequest request = (HttpRequest) messages.get(0);
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/hello", request.uri());

        HttpHeaders headers = (HttpHeaders) messages.get(1);
        assertEquals("example.com", headers.getString(HttpHeaderNames.HOST));

        assertEquals(0, input.queueSize());
    }

    @Test
    public void testRequestDecoderEmitsEmptyLastHeadersWhenHeaderSectionEndsImmediately() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hello HTTP/1.1\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof LastHttpContent);
        assertEquals(0, ((HttpHeaders) messages.get(1)).headerSize());
    }

    @Test
    public void testRequestDecoderDoesNotConsumePartialHeaderLine() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\nUser-Agent: tes"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> firstBatch = output.takeMessage(output.queueSize());
        assertEquals(2, firstBatch.size());
        assertTrue(firstBatch.get(0) instanceof HttpRequest);
        assertTrue(firstBatch.get(1) instanceof HttpHeaders);
        assertFalse(firstBatch.get(1) instanceof LastHttpHeaders);
        assertEquals("example.com", ((HttpHeaders) firstBatch.get(1)).getString(HttpHeaderNames.HOST));
        assertEquals(1, input.queueSize());
        assertEquals("User-Agent: tes", text(input.peekMessage()));

        HttpContext reqCtx = context.context(HttpContext.class);
        assertNotNull(reqCtx);
        assertEquals(HttpContext.DecodePhase.READ_HEADER, reqCtx.req.decoderPhase);
        assertNotNull(reqCtx.req.currentMessage);
        assertEquals("/hello", reqCtx.req.currentMessage.uri());
        assertEquals(-1, reqCtx.req.contentLength);
        assertFalse(reqCtx.req.chunked);

        input.offerMessage(ascii("t\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof LastHttpHeaders);
        assertTrue(messages.get(1) instanceof LastHttpContent);

        HttpHeaders headers = (HttpHeaders) messages.get(0);
        assertNull(headers.getString(HttpHeaderNames.HOST));
        assertEquals("test", headers.getString(HttpHeaderNames.USER_AGENT));
        assertEquals(0, input.queueSize());
    }

    @Test
    public void testRequestDecoderDoesNotConsumePartialHeaderLineWithoutCrLf() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hello HTTP/1.1\r\nX-Desc: hello"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        assertEquals(1, output.queueSize());
        List<HttpObject> firstBatch = output.takeMessage(output.queueSize());
        assertEquals(1, firstBatch.size());
        assertTrue(firstBatch.get(0) instanceof HttpRequest);
        assertEquals("/hello", ((HttpRequest) firstBatch.get(0)).uri());
        assertEquals(1, input.queueSize());
        assertEquals("X-Desc: hello", text(input.peekMessage()));

        HttpContext reqCtx = context.context(HttpContext.class);
        assertNotNull(reqCtx);
        assertEquals(-1, reqCtx.req.contentLength);
        assertFalse(reqCtx.req.chunked);

        input.offerMessage(ascii("\r\nHost: example.com\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof LastHttpHeaders);
        assertTrue(messages.get(1) instanceof LastHttpContent);

        HttpHeaders lastHeaders = (HttpHeaders) messages.get(0);
        assertEquals("hello", lastHeaders.getString("X-Desc"));
        assertEquals("example.com", lastHeaders.getString(HttpHeaderNames.HOST));
    }

    @Test
    public void testRequestDecoderReturnsAllCompleteHeadersInCurrentBatch() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\nUser-Agent: tester\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof HttpHeaders);
        assertFalse(messages.get(1) instanceof LastHttpHeaders);
        assertEquals("example.com", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.HOST));
        assertEquals("tester", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.USER_AGENT));

        HttpContext reqCtx = context.context(HttpContext.class);
        assertEquals(-1, reqCtx.req.contentLength);
        assertFalse(reqCtx.req.chunked);

        input.offerMessage(ascii("\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> lastBatch = output.takeMessage(output.queueSize());
        assertEquals(2, lastBatch.size());
        assertTrue(lastBatch.get(0) instanceof LastHttpHeaders);
        assertTrue(lastBatch.get(1) instanceof LastHttpContent);
        assertEquals(0, ((HttpHeaders) lastBatch.get(0)).headerSize());
    }

    @Test
    public void testRequestDecoderDefersUriAndHeaderValueMaterialization() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());

        DefaultHttpRequest request = (DefaultHttpRequest) messages.get(0);
        Field uriField = DefaultHttpRequest.class.getDeclaredField("uri");
        uriField.setAccessible(true);
        assertNull(uriField.get(request));

        Field methodTextField = DefaultHttpRequest.class.getDeclaredField("methodText");
        methodTextField.setAccessible(true);
        assertTrue(methodTextField.get(request) instanceof StringView);

        Field versionTextField = DefaultHttpRequest.class.getDeclaredField("versionText");
        versionTextField.setAccessible(true);
        assertTrue(versionTextField.get(request) instanceof StringView);

        Field uriTextField = DefaultHttpRequest.class.getDeclaredField("uriText");
        uriTextField.setAccessible(true);
        assertTrue(uriTextField.get(request) instanceof StringView);

        DefaultHttpHeaders headers = (DefaultHttpHeaders) messages.get(1);
        Field entriesField = DefaultHttpHeaders.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<?> entries = (List<?>) entriesField.get(headers);
        assertEquals(1, entries.size());

        Object entry = entries.get(0);
        Field valueField = entry.getClass().getDeclaredField("value");
        valueField.setAccessible(true);
        assertTrue(valueField.get(entry) instanceof StringView);
        assertEquals("example.com", headers.getString(HttpHeaderNames.HOST));
    }

    @Test
    public void testRequestDecoderEmitsSeparatedHeadersAndTrailers() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nHost: example.com\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: done\r\n\r\n"));
        input.sndSubmit();
        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(6, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof HttpContent);
        assertTrue(messages.get(3) instanceof HttpContent);
        assertTrue(messages.get(4) instanceof TrailerHttpHeaders);
        assertFalse(messages.get(4) instanceof LastHttpHeaders);
        assertTrue(messages.get(5) instanceof LastHttpContent);

        HttpRequest request = (HttpRequest) messages.get(0);
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/upload", request.uri());

        HttpHeaders headers = (HttpHeaders) messages.get(1);
        assertEquals("chunked", headers.getString(HttpHeaderNames.TRANSFER_ENCODING));
        assertEquals("example.com", headers.getString(HttpHeaderNames.HOST));

        assertEquals("Wiki", body((HttpContent) messages.get(2)));
        assertEquals("pedia", body((HttpContent) messages.get(3)));

        HttpHeaders trailers = (HttpHeaders) messages.get(4);
        assertEquals("done", trailers.getString("X-Trail"));
        assertEquals(0, ((LastHttpContent) messages.get(5)).content().readableBytes());
    }

    @Test
    public void testRequestDecoderChunkDataMayBeFragmentedAcrossPackets() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWi"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> firstBatch = output.takeMessage(output.queueSize());
        assertEquals(3, firstBatch.size());
        assertTrue(firstBatch.get(0) instanceof HttpRequest);
        assertTrue(firstBatch.get(1) instanceof LastHttpHeaders);
        assertTrue(firstBatch.get(2) instanceof HttpContent);
        assertEquals("Wi", body((HttpContent) firstBatch.get(2)));

        input.offerMessage(ascii("ki\r\n0\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> secondBatch = output.takeMessage(output.queueSize());
        assertEquals(2, secondBatch.size());
        assertTrue(secondBatch.get(0) instanceof HttpContent);
        assertTrue(secondBatch.get(1) instanceof LastHttpContent);
        assertEquals("ki", body((HttpContent) secondBatch.get(0)));
    }

    @Test(expected = HttpBadRequestException.class)
    public void testRequestDecoderRejectsInvalidChunkDelimiter() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        input.offerMessage(ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWikiX\r\n0\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
    }

    @Test
    public void testRequestEncoderSupportsSeparatedHeadersAndTrailers() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader("Host", "example.com").addHeader("Transfer-Encoding", HttpHeaderValues.CHUNKED));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.offerMessage(new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        StringBuilder builder = new StringBuilder();
        for (ByteBuf part : parts) {
            builder.append(text(part));
        }

        assertEquals("POST /upload HTTP/1.1\r\nHost: example.com\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\nX-Trail: done\r\n\r\n", builder.toString());
    }

    @Test
    public void testRequestEncoderStreamsFixedLengthBodyZeroCopy() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.HOST, "example.com").addHeader(HttpHeaderNames.CONTENT_LENGTH, "4"));
        input.offerMessage(new DefaultHttpContent(body));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(3, parts.size());
        assertEquals("POST /upload HTTP/1.1\r\n", text(parts.get(0)));
        assertEquals("host: example.com\r\ncontent-length: 4\r\n\r\n", text(parts.get(1)));
        assertSame(body, parts.get(2));
        assertEquals("Wiki", text(parts.get(2)));
    }

    @Test
    public void testRequestEncoderStreamsChunkBodyZeroCopy() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED));
        input.offerMessage(new DefaultHttpContent(body));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(6, parts.size());
        assertEquals("POST /upload HTTP/1.1\r\n", text(parts.get(0)));
        assertEquals("transfer-encoding: chunked\r\n\r\n", text(parts.get(1)));
        assertEquals("4\r\n", text(parts.get(2)));
        assertSame(body, parts.get(3));
        assertEquals("\r\n", text(parts.get(4)));
        assertEquals("0\r\n\r\n", text(parts.get(5)));
    }

    @Test
    public void testRequestEncoderSupportsFullHttpRequestFixedLength() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", body);
        request.addHeader(HttpHeaderNames.HOST, "example.com");
        request.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(request);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(3, parts.size());
        assertEquals("POST /upload HTTP/1.1\r\n", text(parts.get(0)));
        assertEquals("host: example.com\r\ncontent-length: 4\r\n\r\n", text(parts.get(1)));
        assertSame(request.content(), parts.get(2));
        assertEquals("Wiki", text(parts.get(2)));
    }

    @Test
    public void testRequestEncoderSupportsFullHttpRequestChunked() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", body);
        request.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(request);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(6, parts.size());
        assertEquals("POST /upload HTTP/1.1\r\n", text(parts.get(0)));
        assertEquals("transfer-encoding: chunked\r\n\r\n", text(parts.get(1)));
        assertEquals("4\r\n", text(parts.get(2)));
        assertSame(request.content(), parts.get(3));
        assertEquals("\r\n", text(parts.get(4)));
        assertEquals("0\r\n\r\n", text(parts.get(5)));
    }

    @Test
    public void testRequestEncoderPassesThroughBrokenObjectStream() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);

        encoder.onInit("req-encoder", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        StringBuilder builder = new StringBuilder();
        for (ByteBuf part : parts) {
            builder.append(text(part));
        }

        assertEquals("POST /upload HTTP/1.1\r\n0\r\nX-Trail: done\r\n4\r\nWiki\r\n\r\n", builder.toString());
    }

    @Test
    public void testRequestDecoderTransparentModeWrapsInboundByteBuf() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);
        ByteBuf payload = ascii("raw-ws-frame");

        decoder.onInit("req-decoder", 1, context);
        assertTrue(decoder.onUserEvent(context, userEvent(HttpThroughEvent.enable())));

        input.offerMessage(payload);
        input.sndSubmit();
        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof HttpByteBuf);
        assertSame(payload, ((HttpByteBuf) messages.get(0)).content());
        assertTrue(context.context(HttpContext.class).isTransparentMode());
    }

    @Test
    public void testRequestDecoderDisableTransparentModeResumesHttpParsing() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("req-decoder", 1, context);
        decoder.onUserEvent(context, userEvent(HttpThroughEvent.enable()));
        decoder.onUserEvent(context, userEvent(HttpThroughEvent.disable()));

        input.offerMessage(ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        input.sndSubmit();
        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof HttpRequest);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof LastHttpContent);
        assertFalse(context.context(HttpContext.class).isTransparentMode());
    }

    @Test
    public void testRequestEncoderTransparentModePassesRawByteBuf() throws Throwable {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf payload = ascii("raw-client-frame");

        encoder.onInit("req-encoder", 1, context);
        assertTrue(encoder.onUserEvent(context, userEvent(HttpThroughEvent.enable())));
        input.offerMessage(new DefaultHttpByteBuf(payload));
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> messages = output.takeMessage(output.queueSize());
        assertEquals(1, messages.size());
        assertSame(payload, messages.get(0));
    }

    @Test
    public void testAggregatorMergesInitialHeadersAndTrailers() throws Throwable {
        HttpObjectAggregator aggregator = new HttpObjectAggregator();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        aggregator.onInit("aggregator", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.HOST, "example.com").addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.offerMessage(new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        aggregator.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof FullHttpRequest);

        FullHttpRequest fullRequest = (FullHttpRequest) messages.get(0);
        assertEquals("example.com", fullRequest.getString(HttpHeaderNames.HOST));
        assertEquals("done", fullRequest.getString("X-Trail"));
        assertEquals("4", fullRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
        assertFalse(fullRequest.containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
        assertEquals("Wiki", text(fullRequest.content()));
    }

    @Test
    public void testAggregatorRejectsContentBeforeHeaderSectionCloses() throws Throwable {
        HttpObjectAggregator aggregator = new HttpObjectAggregator();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        aggregator.onInit("aggregator", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultHttpHeaders().addHeader(HttpHeaderNames.HOST, "example.com"));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.sndSubmit();

        try {
            aggregator.onMessage(context, input, output);
            fail("expected HttpProtocolViolationException");
        } catch (HttpProtocolViolationException e) {
            assertTrue(e.getMessage().contains("HttpContent before LastHttpHeaders"));
        }
    }

    @Test
    public void testAggregatorRejectsFullMessageDuringOpenAggregation() throws Throwable {
        HttpObjectAggregator aggregator = new HttpObjectAggregator();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        aggregator.onInit("aggregator", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first"));
        input.offerMessage(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second"));
        input.sndSubmit();

        try {
            aggregator.onMessage(context, input, output);
            fail("expected HttpProtocolViolationException");
        } catch (HttpProtocolViolationException e) {
            assertTrue(e.getMessage().contains("full HTTP message"));
        }
    }

    @Test
    public void testAggregatorTransparentModeResetsStateAndPassesThroughRawFrames() throws Throwable {
        HttpObjectAggregator aggregator = new HttpObjectAggregator();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);
        ByteBuf payload = ascii("raw-aggregator-frame");

        aggregator.onInit("aggregator", 1, context);
        input.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.CONTENT_LENGTH, "4"));
        input.sndSubmit();
        aggregator.onMessage(context, input, output);

        assertTrue(aggregator.onUserEvent(context, userEvent(HttpThroughEvent.enable())));
        input.offerMessage(new DefaultHttpByteBuf(payload));
        input.sndSubmit();
        aggregator.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof HttpByteBuf);
        assertSame(payload, ((HttpByteBuf) messages.get(0)).content());
    }

    @Test
    public void testRequestDecoderOnErrorResetsStateAndDoesNotClearException() throws Throwable {
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        TestProtoContext context = new TestProtoContext();
        decoder.onInit("req-decoder", 1, context);

        HttpContext reqCtx = context.context(HttpContext.class);
        reqCtx.req.decoderPhase = HttpContext.DecodePhase.READ_CHUNKED_CONTENT;
        reqCtx.req.currentMessage = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/broken");
        reqCtx.req.currentHeaders = new DefaultTrailerHttpHeaders();
        reqCtx.req.currentHeadersTrailer = true;
        reqCtx.req.headerBytes = 128;
        reqCtx.req.chunked = true;
        reqCtx.req.contentLength = 64;
        reqCtx.req.bytesRead = 12;
        reqCtx.req.currentChunkSize = 16;
        reqCtx.req.chunkSizeReady = true;
        reqCtx.req.chunkDelimiterReady = true;
        reqCtx.req.trailerComplete = true;
        reqCtx.req.emitEmptyEndContent = true;
        reqCtx.req.packetSequence = 3;

        TestExceptionHolder exceptionHolder = new TestExceptionHolder();
        ProtoStatus status = decoder.onError(context, new HttpBadRequestException("broken request"), exceptionHolder);

        assertEquals(ProtoStatus.Next, status);
        assertFalse(exceptionHolder.cleared);
        assertEquals(HttpContext.DecodePhase.READ_INITIAL, reqCtx.req.decoderPhase);
        assertNull(reqCtx.req.currentMessage);
        assertNull(reqCtx.req.currentHeaders);
        assertFalse(reqCtx.req.currentHeadersTrailer);
        assertEquals(0, reqCtx.req.headerBytes);
        assertFalse(reqCtx.req.chunked);
        assertEquals(-1, reqCtx.req.contentLength);
        assertEquals(0, reqCtx.req.bytesRead);
        assertEquals(0, reqCtx.req.currentChunkSize);
        assertFalse(reqCtx.req.chunkSizeReady);
        assertFalse(reqCtx.req.chunkDelimiterReady);
        assertFalse(reqCtx.req.trailerComplete);
        assertFalse(reqCtx.req.emitEmptyEndContent);
        assertEquals(0, reqCtx.req.packetSequence);
    }

    @Test
    public void testResponseDecoderEmitsFixedLengthBody() throws Throwable {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("resp-decoder", 1, context);
        input.offerMessage(ascii("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nWiki"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof HttpResponse);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof LastHttpContent);

        HttpResponse response = (HttpResponse) messages.get(0);
        assertEquals(200, response.status().code());
        assertEquals("4", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals("Wiki", body((HttpContent) messages.get(2)));
    }

    @Test
    public void testResponseDecoderEmitsSeparatedHeadersAndTrailers() throws Throwable {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> input = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> output = new ProtoQueue<>(-1);

        decoder.onInit("resp-decoder", 1, context);
        input.offerMessage(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nServer: demo\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: done\r\n\r\n"));
        input.sndSubmit();

        decoder.onMessage(context, input, output);
        output.sndSubmit();

        List<HttpObject> messages = output.takeMessage(output.queueSize());
        assertEquals(6, messages.size());
        assertTrue(messages.get(0) instanceof HttpResponse);
        assertTrue(messages.get(1) instanceof LastHttpHeaders);
        assertTrue(messages.get(2) instanceof HttpContent);
        assertTrue(messages.get(3) instanceof HttpContent);
        assertTrue(messages.get(4) instanceof TrailerHttpHeaders);
        assertTrue(messages.get(5) instanceof LastHttpContent);

        HttpResponse response = (HttpResponse) messages.get(0);
        assertEquals(200, response.status().code());

        HttpHeaders headers = (HttpHeaders) messages.get(1);
        assertEquals("chunked", headers.getString(HttpHeaderNames.TRANSFER_ENCODING));
        assertEquals("demo", headers.getString("Server"));

        assertEquals("Wiki", body((HttpContent) messages.get(2)));
        assertEquals("pedia", body((HttpContent) messages.get(3)));

        HttpHeaders trailers = (HttpHeaders) messages.get(4);
        assertEquals("done", trailers.getString("X-Trail"));
        assertEquals(0, ((LastHttpContent) messages.get(5)).content().readableBytes());
    }

    @Test
    public void testResponseDecoderOnErrorResetsStateAndDoesNotClearException() throws Throwable {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        TestProtoContext context = new TestProtoContext();
        decoder.onInit("resp-decoder", 1, context);

        HttpContext respCtx = context.context(HttpContext.class);
        respCtx.resp.decoderPhase = HttpContext.DecodePhase.READ_CHUNKED_CONTENT;
        respCtx.resp.currentMessage = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        respCtx.resp.currentHeaders = new DefaultTrailerHttpHeaders();
        respCtx.resp.currentHeadersTrailer = true;
        respCtx.resp.headerBytes = 128;
        respCtx.resp.contentLength = 64;
        respCtx.resp.bytesRead = 12;
        respCtx.resp.chunked = true;
        respCtx.resp.connectionClose = true;
        respCtx.resp.currentChunkSize = 16;
        respCtx.resp.chunkSizeReady = true;
        respCtx.resp.chunkDelimiterReady = true;
        respCtx.resp.trailerComplete = true;
        respCtx.resp.emitEmptyEndContent = true;
        respCtx.resp.packetSequence = 3;

        TestExceptionHolder exceptionHolder = new TestExceptionHolder();
        ProtoStatus status = decoder.onError(context, new HttpBadRequestException("broken response"), exceptionHolder);

        assertEquals(ProtoStatus.Next, status);
        assertFalse(exceptionHolder.cleared);
        assertEquals(HttpContext.DecodePhase.READ_INITIAL, respCtx.resp.decoderPhase);
        assertNull(respCtx.resp.currentMessage);
        assertNull(respCtx.resp.currentHeaders);
        assertFalse(respCtx.resp.currentHeadersTrailer);
        assertEquals(0, respCtx.resp.headerBytes);
        assertEquals(-1, respCtx.resp.contentLength);
        assertEquals(0, respCtx.resp.bytesRead);
        assertFalse(respCtx.resp.chunked);
        assertFalse(respCtx.resp.connectionClose);
        assertEquals(0, respCtx.resp.currentChunkSize);
        assertFalse(respCtx.resp.chunkSizeReady);
        assertFalse(respCtx.resp.chunkDelimiterReady);
        assertFalse(respCtx.resp.trailerComplete);
        assertFalse(respCtx.resp.emitEmptyEndContent);
        assertEquals(0, respCtx.resp.packetSequence);
    }

    @Test
    public void testResponseEncoderStreamsFixedLengthBodyZeroCopy() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.CONTENT_LENGTH, "4"));
        input.offerMessage(new DefaultHttpContent(body));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(3, parts.size());
        assertEquals("HTTP/1.1 200 OK\r\n", text(parts.get(0)));
        assertEquals("content-length: 4\r\n\r\n", text(parts.get(1)));
        assertSame(body, parts.get(2));
        assertEquals("Wiki", text(parts.get(2)));
    }

    @Test
    public void testResponseEncoderSupportsSeparatedHeadersAndTrailers() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader("Server", "demo").addHeader("Transfer-Encoding", HttpHeaderValues.CHUNKED));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.offerMessage(new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        StringBuilder builder = new StringBuilder();
        for (ByteBuf part : parts) {
            builder.append(text(part));
        }

        assertEquals("HTTP/1.1 200 OK\r\nServer: demo\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\nX-Trail: done\r\n\r\n", builder.toString());
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseFixedLength() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
        response.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(response);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(3, parts.size());
        assertEquals("HTTP/1.1 200 OK\r\n", text(parts.get(0)));
        assertEquals("content-length: 4\r\n\r\n", text(parts.get(1)));
        assertSame(response.content(), parts.get(2));
        assertEquals("Wiki", text(parts.get(2)));
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseChunked() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
        response.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(response);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(6, parts.size());
        assertEquals("HTTP/1.1 200 OK\r\n", text(parts.get(0)));
        assertEquals("transfer-encoding: chunked\r\n\r\n", text(parts.get(1)));
        assertEquals("4\r\n", text(parts.get(2)));
        assertSame(response.content(), parts.get(3));
        assertEquals("\r\n", text(parts.get(4)));
        assertEquals("0\r\n\r\n", text(parts.get(5)));
    }

    @Test
    public void testResponseEncoderStreamsChunkBodyZeroCopy() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);
        ByteBuf body = ascii("Wiki");

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK));
        input.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED));
        input.offerMessage(new DefaultHttpContent(body));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        assertEquals(6, parts.size());
        assertEquals("HTTP/1.1 200 OK\r\n", text(parts.get(0)));
        assertEquals("transfer-encoding: chunked\r\n\r\n", text(parts.get(1)));
        assertEquals("4\r\n", text(parts.get(2)));
        assertSame(body, parts.get(3));
        assertEquals("\r\n", text(parts.get(4)));
        assertEquals("0\r\n\r\n", text(parts.get(5)));
    }

    @Test
    public void testResponseEncoderPassesThroughBrokenObjectStream() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<HttpObject> input = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> output = new ProtoQueue<>(-1);

        encoder.onInit("resp-encoder", 1, context);
        input.offerMessage(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK));
        input.offerMessage(new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"));
        input.offerMessage(new DefaultHttpContent(ascii("Wiki")));
        input.offerMessage(DefaultLastHttpContent.EMPTY);
        input.sndSubmit();

        encoder.onMessage(context, input, output);
        output.sndSubmit();

        List<ByteBuf> parts = output.takeMessage(output.queueSize());
        StringBuilder builder = new StringBuilder();
        for (ByteBuf part : parts) {
            builder.append(text(part));
        }

        assertEquals("HTTP/1.1 200 OK\r\n0\r\nX-Trail: done\r\n4\r\nWiki\r\n\r\n", builder.toString());
    }

    @Test
    public void testServerDuplexeTransparentModeCoversBothDirections() throws Throwable {
        HttpServerDuplexe duplexe = new HttpServerDuplexe();
        TestProtoContext context = new TestProtoContext();
        ProtoQueue<ByteBuf> rcvInput = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> rcvOutput = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> sndInput = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> sndOutput = new ProtoQueue<>(-1);
        ByteBuf inbound = ascii("raw-server-inbound");
        ByteBuf outbound = ascii("raw-server-outbound");

        duplexe.onInit("server-http", 1, 1, context);
        assertTrue(duplexe.onUserEvent(context, userEvent(HttpThroughEvent.enable()), true));

        rcvInput.offerMessage(inbound);
        rcvInput.sndSubmit();
        duplexe.onMessage(context, true, rcvInput, rcvOutput, sndInput, sndOutput);
        rcvOutput.sndSubmit();

        List<HttpObject> inboundMessages = rcvOutput.takeMessage(rcvOutput.queueSize());
        assertEquals(1, inboundMessages.size());
        assertTrue(inboundMessages.get(0) instanceof HttpByteBuf);
        assertSame(inbound, ((HttpByteBuf) inboundMessages.get(0)).content());

        sndInput.offerMessage(new DefaultHttpByteBuf(outbound));
        sndInput.sndSubmit();
        duplexe.onMessage(context, false, rcvInput, rcvOutput, sndInput, sndOutput);
        sndOutput.sndSubmit();

        List<ByteBuf> outboundMessages = sndOutput.takeMessage(sndOutput.queueSize());
        assertEquals(1, outboundMessages.size());
        assertSame(outbound, outboundMessages.get(0));
    }

    private static ByteBuf ascii(String value) {
        return ByteBuf.wrap(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String body(HttpContent content) {
        return text(content.content());
    }

    private static String text(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static SoUserEvent userEvent(final Object eventData) {
        return new SoUserEvent() {
            @Override
            public SoChannel<?> getSource() {
                return TestProtoContext.sharedChannel;
            }

            @Override
            public Class<?> getEventType() {
                return eventData.getClass();
            }

            @Override
            public Object getData() {
                return eventData;
            }
        };
    }

    private static class TestProtoContext implements ProtoContext {
        private static final VrtChannel sharedChannel = createSharedChannel();
        private final Map<Class<?>, Object> local = new HashMap<>();
        private final Map<Class<?>, Object> root = new HashMap<>();
        private final Map<String, Object> flash = new HashMap<>();
        private final NetConfig config = new NetConfig();

        private static VrtChannel createSharedChannel() {
            try {
                NetManager neta = new NetManager();
                return (VrtChannel) neta.connectSync(new VrtSocketAddress(99), ctx -> {
                }, VrtSoConfig.asServer());
            } catch (Throwable e) {
                throw new RuntimeException("failed to create VrtChannel for tests", e);
            }
        }

        @Override
        public NetConfig getConfig() {
            return this.config;
        }

        @Override
        public SoChannel<?> getChannel() {
            return sharedChannel;
        }

        @Override
        public SoContext getSoContext() {
            return null;
        }

        @Override
        public String getStackName() {
            return null;
        }

        @Override
        public <T> T context(Class<T> attachment) {
            return attachment.cast(this.local.get(attachment));
        }

        @Override
        public <T> T context(Class<T> attachmentType, T attachment) {
            this.local.put(attachmentType, attachment);
            return attachment;
        }

        @Override
        public <T> T rootContext(Class<T> type) {
            return type.cast(this.root.get(type));
        }

        @Override
        public <T> T rootContext(Class<T> type, T value) {
            this.root.put(type, value);
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T flash(String key) {
            return (T) this.flash.get(key);
        }

        @Override
        public <T> T flash(String key, T value) {
            if (value == null) {
                this.flash.remove(key);
            } else {
                this.flash.put(key, value);
            }
            return value;
        }

        @Override
        public Future<?> sendData(Object writeData) {
            return null;
        }

        @Override
        public <T> void fireUserEvent(Class<T> eventType, T event) {
        }

        @Override
        public Future<?> flush() {
            return null;
        }

        @Override
        public ByteBufAllocator byteBufAllocator() {
            return ByteBufAllocator.DEFAULT;
        }

        @Override
        public boolean isRcv() {
            return true;
        }

        @Override
        public boolean isSnd() {
            return false;
        }

        @Override
        public void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        }

        @Override
        public void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        }

        @Override
        public void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        }

        @Override
        public void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        }

        @Override
        public void addFirstEncoder(ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addFirstEncoder(String name, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addLastEncoder(ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addLastEncoder(String name, ProtoHandler<?, ?> encoder) {
        }

        @Override
        public void addFirstDecoder(ProtoHandler<?, ?> decoder) {
        }

        @Override
        public void addFirstDecoder(String name, ProtoHandler<?, ?> decoder) {
        }

        @Override
        public void addLastDecoder(ProtoHandler<?, ?> decoder) {
        }

        @Override
        public void addLastDecoder(String name, ProtoHandler<?, ?> decoder) {
        }
    }

    private static class TestExceptionHolder implements ProtoExceptionHolder {
        private boolean cleared;

        @Override
        public void clear() {
            this.cleared = true;
        }
    }
}