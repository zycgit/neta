/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpInitialLineScannerTest extends AbstractHttpTest {
    @Test
    public void completeLineBorrowsInputWithoutAllocatingScratch() {
        try (Input input = new Input()) {
            ByteBuf packet = ascii("skipGET / HTTP/1.1\r\nHost: stable\r\n\r\nbody");
            packet.skipReadableBytes(4);
            input.src.offerMessage(packet);
            long allocations = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
            assertSame(packet, input.read(64));
            assertNull(input.state.initialLineBuffer);
            assertEquals(allocations, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
            input.consume(packet);
            assertEquals("Host: stable\r\n\r\nbody", packet.getString(0, packet.readableBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    public void partialLineReleasesEachPacketAndLeavesHeadersInTheirInput() {
        try (Input input = new Input()) {
            byte[] prefix = "POST /upload HT".getBytes(StandardCharsets.US_ASCII);
            ByteBuf first = ByteBuf.wrap(prefix);
            input.src.offerMessage(first.retain());
            try {
                assertNull(input.read(64));
                assertEquals(0, input.src.queueSize());
                assertEquals(1, first.refCnt());
                prefix[0] = 'X';
                ByteBuf last = ascii("TP/1.1\r\nHost: stable\r\n\r\nbody");
                input.src.offerMessage(ByteBuf.EMPTY);
                input.src.offerMessage(last);
                ByteBuf line = input.read(64);
                assertSame(input.state.initialLineBuffer, line);
                assertEquals("POST /upload HTTP/1.1\r\n", line.getString(0, line.readableBytes(), StandardCharsets.US_ASCII));
                input.consume(line);
                assertTrue(line.isFree());
                assertNull(input.state.initialLineBuffer);
                assertSame(last, input.src.peekMessage());
                assertEquals("Host: stable\r\n\r\nbody", last.getString(0, last.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                first.release();
            }
        }
    }

    @Test
    public void oneByteReadOnlyPacketsGrowWithinTheConfiguredBound() {
        try (Input input = new Input()) {
            String text = "GET /" + "x".repeat(240) + " HTTP/1.1\r\n";
            byte[] wire = text.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < wire.length; i++) {
                ByteBuffer packet = ByteBuffer.allocateDirect(3);
                packet.put((byte) 'x').put(wire[i]).flip();
                ByteBuf buffer = ByteBuf.wrap(packet.asReadOnlyBuffer());
                buffer.skipReadableBytes(1);
                input.src.offerMessage(buffer);
                ByteBuf old = input.state.initialLineBuffer;
                int oldCapacity = old == null ? 0 : old.capacity();
                ByteBuf result = input.read(wire.length - 2);
                assertTrue(input.state.initialLineBuffer.capacity() <= wire.length);
                if (input.state.initialLineBuffer.capacity() > oldCapacity && old != null) {
                    assertTrue(old.isFree());
                }
                if (i < wire.length - 1) {
                    assertNull(result);
                    assertEquals(0, input.src.queueSize());
                } else {
                    assertEquals(text, result.getString(0, result.readableBytes(), StandardCharsets.US_ASCII));
                    input.consume(result);
                }
            }
            assertNull(input.state.initialLineBuffer);
        }
    }

    @Test
    public void exactLimitAcceptsLfAndCrLfAtEverySplit() {
        for (String ending : new String[] { "\n", "\r\n" }) {
            String text = "x".repeat(64) + ending;
            for (int split = 0; split <= text.length(); split++) {
                try (Input input = new Input()) {
                    input.src.offerMessage(ascii(text.substring(0, split)));
                    ByteBuf result = input.read(64);
                    if (split < text.length()) {
                        assertNull(result);
                        input.src.offerMessage(ascii(text.substring(split) + "tail"));
                        result = input.read(64);
                    }
                    assertNotNull(result);
                    assertEquals(text.length() - 1, input.state.initialLineFeedIndex);
                    assertEquals(text, result.getString(0, text.length(), StandardCharsets.US_ASCII));
                    input.consume(result);
                    assertNull(input.state.initialLineBuffer);
                }
            }
        }
    }

    @Test
    public void tinyLimitDoesNotAllocateTheDefaultCapacity() {
        try (Input input = new Input()) {
            for (String fragment : new String[] { "x", "\r" }) {
                input.src.offerMessage(ascii(fragment));
                assertNull(input.read(1));
                assertEquals(3, input.state.initialLineBuffer.capacity());
            }
            input.src.offerMessage(ascii("\nnext"));
            ByteBuf line = input.read(1);
            assertEquals(2, input.state.initialLineFeedIndex);
            input.consume(line);
            assertEquals("next", input.src.peekMessage().getString(0, 4, StandardCharsets.US_ASCII));
        }
    }

    @Test
    public void rejectsUnterminatedOversizeBeforeGrowingStorage() {
        for (boolean fragmented : new boolean[] { false, true }) {
            try (Input input = new Input()) {
                if (fragmented) {
                    input.src.offerMessage(ascii("x".repeat(32)));
                    assertNull(input.read(32));
                }
                input.src.offerMessage(ascii("x".repeat(1000)));
                try {
                    input.read(32);
                    fail("Expected an initial line limit failure without waiting for LF");
                } catch (HttpInitialLineTooLongException expected) {
                    assertEquals(32, expected.limit());
                }
                assertTrue(input.state.initialLineBuffer == null || input.state.initialLineBuffer.capacity() <= 34);
                input.state.releaseAndReset();
                assertNull(input.state.initialLineBuffer);
            }
        }
    }

    @Test
    public void maximumIntegerLimitDoesNotOverflowTheScanOrGrowthBound() {
        try (Input input = new Input()) {
            input.src.offerMessage(ascii("GET /"));
            assertNull(input.read(Integer.MAX_VALUE));
            input.src.offerMessage(ascii(" HTTP/1.1\r\n"));
            ByteBuf result = input.read(Integer.MAX_VALUE);
            assertNotNull(result);
            input.consume(result);
            input.src.offerMessage(ascii("GET /next HTTP/1.1\r\n"));
            result = input.read(Integer.MAX_VALUE);
            assertNotNull(result);
            input.consume(result);
        }
    }

    @Test
    public void requestAndResponseKeepIndependentPartialLines() {
        HttpContext context = new HttpContext();
        try (Input request = new Input(context.req); Input response = new Input(context.resp)) {
            request.src.offerMessage(ascii("GET /"));
            response.src.offerMessage(ascii("HTTP/1.1 "));
            assertNull(request.read(64));
            assertNull(response.read(64));
            context.req.releaseAndReset();
            assertNotNull(context.resp.initialLineBuffer);
            response.src.offerMessage(ascii("200 OK\r\n"));
            ByteBuf line = response.read(64);
            assertEquals("HTTP/1.1 200 OK\r\n", line.getString(0, line.readableBytes(), StandardCharsets.US_ASCII));
            response.consume(line);
        }
    }

    @Test
    public void partialLineStorageIsReleasedOnDecoderSyntaxErrors() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            for (String wire : request ? new String[] { "BAD\r\n", "GET / BAD\r\n" } : new String[] { "HTTP/1.1 nope\r\n", "BAD 200 OK\r\n" }) {
                autoCloseNeta(neta -> {
                    VirtualPipe pipe = pipe(neta, request, 64);
                    List<HttpObject> out = new ArrayList<>();
                    try {
                        out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(0, 2))));
                        assertNotNull(state(pipe, request).initialLineBuffer);
                        out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(2))));
                        assertFalse(pipe.channelInboundErrors().isEmpty());
                        assertNull(state(pipe, request).initialLineBuffer);
                    } finally {
                        free(out);
                    }
                });
            }
        }
    }

    @Test
    public void decodersRejectOverLimitWithAndWithoutLineTerminators() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            String valid = request ? "GET /bounded HTTP/1.1" : "HTTP/1.1 299 bounded";
            for (String ending : new String[] { "", "\n", "\r\n" }) {
                for (boolean fragmented : new boolean[] { false, true }) {
                    autoCloseNeta(neta -> {
                        VirtualPipe pipe = pipe(neta, request, valid.length());
                        List<HttpObject> out = new ArrayList<>();
                        try {
                            if (fragmented) {
                                out.addAll(receiveAndIntBound(pipe, ascii(valid)));
                                assertTrue(out.isEmpty());
                                out.addAll(receiveAndIntBound(pipe, ascii("x" + ending)));
                            } else {
                                out.addAll(receiveAndIntBound(pipe, ascii(valid + "x" + ending)));
                            }
                            assertFalse(pipe.channelInboundErrors().isEmpty());
                            assertNull(state(pipe, request).initialLineBuffer);
                        } finally {
                            free(out);
                        }
                    });
                }
            }
        }
    }

    @Test
    public void closingEitherDecoderReleasesPartialLine() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            HttpContext.DecodeState<?>[] saved = new HttpContext.DecodeState<?>[1];
            long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
            long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
            autoCloseNeta(neta -> {
                VirtualPipe pipe = pipe(neta, request, 512);
                assertTrue(receiveAndIntBound(pipe, ascii(request ? "GET /partial" : "HTTP/1.1 200 part")).isEmpty());
                saved[0] = state(pipe, request);
                assertNotNull(saved[0].initialLineBuffer);
            });
            assertNull(saved[0].initialLineBuffer);
            assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
            assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
        }
    }

    @Test
    public void transparentSwitchReleasesPartialLineAndResumesCleanly() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            autoCloseNeta(neta -> {
                VirtualPipe pipe = pipe(neta, request, 512);
                assertTrue(receiveAndIntBound(pipe, ascii(request ? "GET /partial" : "HTTP/1.1 200 part")).isEmpty());
                pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
                assertNull(state(pipe, request).initialLineBuffer);
                List<HttpObject> raw = receiveAndIntBound(pipe, ascii("opaque"));
                try {
                    assertEquals(1, raw.size());
                    assertTrue(raw.get(0) instanceof HttpByteBuf);
                } finally {
                    free(raw);
                }
                pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
                List<HttpObject> out = receiveAndIntBound(pipe, ascii(request ? "GET /next HTTP/1.1\r\n\r\n" : "HTTP/1.1 204 No Content\r\n\r\n"));
                try {
                    assertEquals(3, out.size());
                    assertTrue(pipe.channelInboundErrors().isEmpty());
                    assertTrue(out.get(2) instanceof LastHttpContent);
                } finally {
                    free(out);
                }
            });
        }
    }

    @Test
    public void longStatusReasonSurvivesGrowthEmptyLinesAndLaterMessages() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = pipe(neta, false, 512);
            String reason = "custom".repeat(50);
            String wire = "\r\n\nHTTP/1.1 299 " + reason + "\nContent-Length: 0\n\n";
            List<HttpObject> out = new ArrayList<>();
            try {
                for (int offset = 0; offset < wire.length(); offset += 7) {
                    out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(offset, Math.min(offset + 7, wire.length())))));
                }
                free(receiveAndIntBound(pipe, ascii("HTTP/1.1 204 No Content\r\n\r\n")));
                assertTrue(pipe.channelInboundErrors().isEmpty());
                HttpResponse response = (HttpResponse) out.get(0);
                assertEquals(299, response.status().code());
                assertEquals(reason, response.status().reasonPhrase());
                assertNull(state(pipe, false).initialLineBuffer);
            } finally {
                free(out);
            }
        });
    }

    private VirtualPipe pipe(net.hasor.neta.channel.NetManager neta, boolean request, int limit) throws Throwable {
        return openVirtualPipe(neta, ctx -> {
            if (request) {
                ctx.addLastDecoder("decoder", new HttpRequestDecoder(limit, 8192, 8192));
            } else {
                ctx.addLastDecoder("decoder", new HttpResponseDecoder(limit, 8192, 8192));
            }
        }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
    }

    private static HttpContext.DecodeState<?> state(VirtualPipe pipe, boolean request) {
        HttpContext context = pipe.channel().findProtoContext(HttpContext.class);
        return request ? context.req : context.resp;
    }

    private static class Input implements AutoCloseable {
        final ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        final HttpContext.DecodeState<?> state;
        final long activeCount = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        final long activeBytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();

        Input() {
            this(new HttpContext.RequestDecodeState());
        }

        Input(HttpContext.DecodeState<?> state) {
            this.state = state;
        }

        ByteBuf read(int limit) {
            return HttpInitialLineScanner.read(this.src, this.state, limit);
        }

        void consume(ByteBuf line) {
            HttpInitialLineScanner.consume(this.state, line, this.state.initialLineFeedIndex + 1);
        }

        @Override
        public void close() {
            this.src.clearAndRelease();
            this.state.releaseAndReset();
            assertEquals(this.activeCount, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
            assertEquals(this.activeBytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
        }
    }
}
