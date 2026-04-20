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

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.junit.Test;

import net.hasor.cobble.ref.Tuple;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetConfig;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.data.ProtoSndQueueView;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;

public class HttpResponseEncoderTest extends AbstractHttpTest {
    @Test
    public void testResponseEncoderSupportsSeparatedHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of("Server", "demo"), Tuple.of("Transfer-Encoding", HttpHeaderValues.CHUNKED)),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    joinHeaders(DefaultTrailerHttpHeaders.class, Tuple.of("X-Trail", "done")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("HTTP/1.1 200 OK\r\nServer: demo\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\nX-Trail: done\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseFixedLength() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("Wiki"));
            response.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals(3, parts.size());
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\nWiki", text(parts));
        });
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseChunked() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("Wiki"));
            response.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals(6, parts.size());
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseFullObjectDelegatesAppendHeaders() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
            response.appendHeaders(joinHeaders(DefaultLastHttpHeaders.class, Tuple.of("X-Response", "resp")));

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals("HTTP/1.1 200 OK\r\nX-Response: resp\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseEncoderStreamsChunkBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            ByteBuf body = ascii("Wiki");
            DefaultHttpContent content = new DefaultHttpContent(body);
            List<ByteBuf> outbound = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK),//
                    new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED),//
                    content,//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertSame(body, outbound.get(3));
            assertNull(content.content());
            assertEquals(1, body.refCnt());
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderStreamsFixedLengthBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
            DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");
            ByteBuf body = ascii("Wiki");
            DefaultHttpContent content = new DefaultHttpContent(body);

            List<ByteBuf> outbound = sendAndOutBound(pipe, response, headers, content, new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertSame(body, outbound.get(2));
            assertNull(content.content());
            assertEquals(1, body.refCnt());
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\nWiki", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderTransparentModePassesRawByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.isTransparentMode());

            ByteBuf payload = ascii("raw-response-outbound");
            DefaultHttpByteBuf source = new DefaultHttpByteBuf(payload);
            List<ByteBuf> outbound = sendAndOutBound(pipe, source);
            assertSame(payload, outbound.get(0));
            assertNull(source.content());
            assertEquals(1, payload.refCnt());
            assertEquals("raw-response-outbound", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderDisableTransparentModeResumesHttpEncoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.switchTransparentMode(false));
            assertFalse(httpContext.isTransparentMode());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.ACCEPTED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));

            assertEquals(3, parts.size());
            assertEquals("HTTP/1.1 202 Accepted\r\ncontent-type: application/json\r\ncontent-length: 2\r\n\r\n{}", text(parts));
        });
    }

    @Test
    public void testResponseEncoderStopsOnBackpressureBeforeTakingTransferredBody() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        ProtoContext context = mockContext(ByteBufAllocator.DEFAULT);
        encoder.onInit("resp-encoder", 1, context);

        HttpContext httpContext = context.context(HttpContext.class);
        assertNotNull(httpContext);
        assertTrue(httpContext.switchTransparentMode(true));

        ByteBuf body = ascii("reject-me");
        DefaultHttpByteBuf source = new DefaultHttpByteBuf(body);
        SimpleProtoRcvQueue<HttpObject> src = new SimpleProtoRcvQueue<>();
        src.add(source);
        AdjustableProtoSndQueue<ByteBuf> dst = new AdjustableProtoSndQueue<>(0);

        ProtoStatus firstStatus = encoder.onMessage(context, src, dst);
        assertEquals(ProtoStatus.Stop, firstStatus);
        assertSame(source, src.peekMessage());
        assertSame(body, source.content());
        assertEquals(1, body.refCnt());
        assertTrue(dst.offered.isEmpty());

        dst.setCapacity(1);
        ProtoStatus secondStatus = encoder.onMessage(context, src, dst);
        assertEquals(ProtoStatus.Next, secondStatus);
        assertNull(source.content());
        assertEquals(1, dst.offered.size());
        assertSame(body, dst.offered.get(0));
        assertEquals(1, body.refCnt());
    }

    @Test
    public void testResponseEncoderStopsBeforeAllocatingStatusLineBufferWhenOutboundHasNoSlot() throws Throwable {
        TrackingByteBufAllocator allocator = new TrackingByteBufAllocator(ByteBufAllocator.DEFAULT);
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        ProtoContext context = mockContext(allocator.proxy());
        encoder.onInit("resp-encoder", 1, context);

        SimpleProtoRcvQueue<HttpObject> src = new SimpleProtoRcvQueue<>();
        src.add(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK));
        AdjustableProtoSndQueue<ByteBuf> dst = new AdjustableProtoSndQueue<>(0);

        ProtoStatus status = encoder.onMessage(context, src, dst);

        assertEquals(ProtoStatus.Stop, status);
        assertTrue(allocator.allocated.isEmpty());
        assertEquals(1, src.queueSize());
        assertTrue(dst.offered.isEmpty());
    }

    @Test
    public void testResponseEncoderStopsWhenChunkContentNeedsMoreSlots() throws Throwable {
        HttpResponseEncoder encoder = new HttpResponseEncoder();
        ProtoContext context = mockContext(ByteBufAllocator.DEFAULT);
        encoder.onInit("resp-encoder", 1, context);

        HttpContext httpContext = context.context(HttpContext.class);
        assertNotNull(httpContext);
        httpContext.respEnc.chunkedEncoding = true;

        ByteBuf body = ascii("Wiki");
        DefaultHttpContent content = new DefaultHttpContent(body);
        SimpleProtoRcvQueue<HttpObject> src = new SimpleProtoRcvQueue<>();
        src.add(content);
        AdjustableProtoSndQueue<ByteBuf> dst = new AdjustableProtoSndQueue<>(2);

        ProtoStatus status = encoder.onMessage(context, src, dst);

        assertEquals(ProtoStatus.Stop, status);
        assertSame(content, src.peekMessage());
        assertSame(body, content.content());
        assertEquals(1, body.refCnt());
        assertTrue(dst.offered.isEmpty());
    }

    private static ProtoContext mockContext(ByteBufAllocator allocator) {
        Map<Class<?>, Object> contextMap = new ConcurrentHashMap<>();
        NetConfig config = new NetConfig();
        config.setBufAllocator(allocator);
        return (ProtoContext) Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return allocator;
            }
            if ("getConfig".equals(method.getName())) {
                return config;
            }
            if ("context".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                } else if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
            }
            return null;
        });
    }

    private static class TrackingByteBufAllocator {
        private final ByteBufAllocator delegate;
        private final List<ByteBuf>    allocated = new ArrayList<>();

        private TrackingByteBufAllocator(ByteBufAllocator delegate) {
            this.delegate = delegate;
        }

        private ByteBufAllocator proxy() {
            return (ByteBufAllocator) Proxy.newProxyInstance(ByteBufAllocator.class.getClassLoader(), new Class[] { ByteBufAllocator.class }, (proxy, method, args) -> {
                Object result = method.invoke(this.delegate, args);
                if (result instanceof ByteBuf && "buffer".equals(method.getName())) {
                    this.allocated.add((ByteBuf) result);
                }
                return result;
            });
        }
    }

    private static class AdjustableProtoSndQueue<T> implements ProtoSndQueue<T> {
        private final List<T> offered = new ArrayList<>();
        private int           capacity;

        private AdjustableProtoSndQueue(int capacity) {
            this.capacity = capacity;
        }

        private void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int getCapacity() {
            return this.capacity;
        }

        @Override
        public int slotSize() {
            return Math.max(0, this.capacity - this.offered.size());
        }

        @Override
        public boolean offerMessage(T[] offerList) {
            return offerList != null && this.offerMessage(Arrays.asList(offerList));
        }

        @Override
        public boolean offerMessage(List<T> offerList) {
            if (offerList == null || offerList.isEmpty() || this.slotSize() < offerList.size()) {
                return false;
            }
            this.offered.addAll(offerList);
            return true;
        }

        @Override
        public boolean offerMessage(ProtoRcvQueue<T> offerList) {
            if (offerList == null || offerList.queueSize() <= 0 || this.slotSize() < offerList.queueSize()) {
                return false;
            }
            this.offered.addAll(offerList.takeMessage(offerList.queueSize()));
            return true;
        }

        @Override
        public ProtoSndQueueView<T> subQueue(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> subKeys() {
            return Collections.emptyList();
        }

        @Override
        public boolean hasSub(String key) {
            return false;
        }
    }

    private static class SimpleProtoRcvQueue<T> implements ProtoRcvQueue<T> {
        private final List<T> list = new ArrayList<>();

        private void add(T item) {
            this.list.add(item);
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int queueSize() {
            return this.list.size();
        }

        @Override
        public List<T> takeMessage(int cnt) {
            if (this.list.isEmpty()) {
                return Collections.emptyList();
            }
            int take = Math.min(cnt, this.list.size());
            List<T> result = new ArrayList<>(this.list.subList(0, take));
            this.list.subList(0, take).clear();
            return result;
        }

        @Override
        public List<T> peekMessage(int cnt) {
            if (this.list.isEmpty()) {
                return Collections.emptyList();
            }
            int take = Math.min(cnt, this.list.size());
            return new ArrayList<>(this.list.subList(0, take));
        }

        @Override
        public void skipMessage(int cnt) {
            int skip = Math.min(cnt, this.list.size());
            this.list.subList(0, skip).clear();
        }

        @Override
        public void drainToQueue(String key, int cnt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void drainToQueue(String key, int cnt, Predicate<T> predicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void discard(String key) {
        }

        @Override
        public List<String> queueNames() {
            return Collections.emptyList();
        }

        @Override
        public boolean hasQueue(String key) {
            return false;
        }

        @Override
        public ProtoRcvQueueView<T> queueView(String key) {
            throw new UnsupportedOperationException();
        }
    }
}