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
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpHeaderStreamingScannerTest {
    @Test
    public void multiplePartialLinesRemainIndependentAfterGrowthAndReset() {
        long activeCount = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long activeBytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
        try {
            String longValue = "v".repeat(180);
            for (String chunk : new String[] { "X-One: fir", "st\r\nX-Two: sec", "ond\r\nX-Long: " + longValue.substring(0, 30), longValue.substring(30) + "\r", "\n\r\n" }) {
                src.offerMessage(ByteBuf.wrap(chunk.getBytes(StandardCharsets.US_ASCII)));
                HttpHeaderStreamingScanner.scan(src, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                    entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                    return true;
                });
            }
            state.releaseAndReset();
            src.clearAndRelease();
            assertEquals(3, entries.size());
            assertEquals("X-One", entries.get(0).getName());
            assertEquals("first", entries.get(0).getValue());
            assertEquals("X-Two", entries.get(1).getName());
            assertEquals("second", entries.get(1).getValue());
            assertEquals("X-Long", entries.get(2).getName());
            assertEquals(longValue, entries.get(2).getValue());
        } finally {
            entries.forEach(DefaultHttpHeaderEntry::release);
            src.clearAndRelease();
            state.releaseAndReset();
        }
        assertEquals(activeCount, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(activeBytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void rejectingCompleteLinesPreservesQueuedBodyAndExternalReference() {
        ByteBuf input = ByteBuf.wrap("X-One: first\r\nX-Two: second\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII));
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        try {
            src.offerMessage(input.retain());
            int[] lines = { 0 };
            assertTrue(HttpHeaderStreamingScanner.scan(src, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                lines[0]++;
                assertFalse(line.isFree());
                return false;
            }));
            assertEquals(2, lines[0]);
            assertSame(input, src.peekMessage());
            assertEquals("body", input.getString(0, 4, StandardCharsets.US_ASCII));
            assertEquals(2, input.refCnt());
            src.clearAndRelease();
            assertEquals(1, input.refCnt());
        } finally {
            src.clearAndRelease();
            state.releaseAndReset();
            input.release();
        }
    }

    @Test
    public void sharedChunkKeepsIndependentHeaderLifetimes() {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
        ByteBuf[] shared = new ByteBuf[1];
        try {
            ByteBuf input = ByteBuf.wrap("skipX-One: first\r\nX-Two: second\r\nX-Empty:\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII));
            input.skipReadableBytes(4);
            src.offerMessage(input);
            assertTrue(HttpHeaderStreamingScanner.scan(src, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                if (shared[0] == null) {
                    shared[0] = line;
                } else {
                    assertSame(shared[0], line);
                }
                entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                return true;
            }));
            assertEquals("body", src.peekMessage().getString(0, 4, StandardCharsets.US_ASCII));
            src.clearAndRelease();
            state.releaseAndReset();
            assertEquals(3, entries.size());
            DefaultHttpHeaderEntry second = entries.get(1).retain();
            try {
                assertEquals("X-One", entries.get(0).getName());
                assertEquals("first", entries.get(0).getValue());
                entries.forEach(DefaultHttpHeaderEntry::release);
                entries.clear();
                assertEquals("X-Two", second.getName());
                assertEquals("second", second.getValue());
            } finally {
                second.release();
            }
        } finally {
            entries.forEach(DefaultHttpHeaderEntry::release);
            src.clearAndRelease();
            state.releaseAndReset();
        }
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void consumerFailureReleasesOnlyItsSharedChunkReference() {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
        try {
            src.offerMessage(ByteBuf.wrap("X-One: first\r\nX-Two: second\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            try {
                HttpHeaderStreamingScanner.scan(src, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                    if (!entries.isEmpty()) {
                        throw new IllegalArgumentException("consumer failure");
                    }
                    entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                    return true;
                });
                fail("Expected consumer failure");
            } catch (IllegalArgumentException expected) {
                assertEquals("consumer failure", expected.getMessage());
            }
            src.clearAndRelease();
            state.releaseAndReset();
            assertEquals("X-One", entries.get(0).getName());
            assertEquals("first", entries.get(0).getValue());
        } finally {
            entries.forEach(DefaultHttpHeaderEntry::release);
            src.clearAndRelease();
            state.releaseAndReset();
        }
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void retainsHeadersAtEveryPacketBoundary() {
        String value = "v".repeat(180);
        byte[] headers = ("X-Long: " + value + "\r\nX-Empty:\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        for (boolean direct : new boolean[] { false, true }) {
            for (int split = 1; split < headers.length; split++) {
                long activeCount = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
                long activeBytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
                ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
                HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
                List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
                HttpHeaderStreamingScanner.HeaderLineConsumer<HttpContext.RequestDecodeState> consumer = (decodeState, line, ns, nl, vs, vl) -> {
                    entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                    return true;
                };
                try {
                    assertTrue(src.offerMessage(input(headers, 0, split, direct)));
                    assertFalse(HttpHeaderStreamingScanner.scan(src, state, 512, consumer));
                    assertTrue(src.offerMessage(input(headers, split, headers.length - split, direct)));
                    assertTrue(src.offerMessage(ByteBuf.wrap(new byte[] { 1, 2, 3 })));
                    assertTrue(HttpHeaderStreamingScanner.scan(src, state, 512, consumer));
                    assertEquals(1, src.queueSize());
                    assertEquals(3, src.peekMessage().readableBytes());
                    src.clearAndRelease();
                    state.releaseAndReset();
                    assertEquals(2, entries.size());
                    assertEquals("X-Long", entries.get(0).getName());
                    assertEquals(value, entries.get(0).getValue());
                    assertEquals("X-Empty", entries.get(1).getName());
                    assertEquals("", entries.get(1).getValue());
                } finally {
                    entries.forEach(DefaultHttpHeaderEntry::release);
                    src.clearAndRelease();
                    state.releaseAndReset();
                }
                assertEquals(activeCount, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
                assertEquals(activeBytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
            }
        }
    }

    @Test
    public void growsPartialLineAndReleasesItOnAbort() {
        long activeCount = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long activeBytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        try {
            byte[] partial = ("X-Long: " + "v".repeat(180)).getBytes(StandardCharsets.US_ASCII);
            for (byte value : partial) {
                src.offerMessage(ByteBuf.wrap(new byte[] { value }));
                assertFalse(HttpHeaderStreamingScanner.scan(src, state, 512, (decodeState, line, ns, nl, vs, vl) -> {
                    fail("Incomplete header must not be emitted");
                    return false;
                }));
            }
        } finally {
            src.clearAndRelease();
            state.releaseAndReset();
        }
        assertEquals(activeCount, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(activeBytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void acceptsPartialLineWithMaximumConfiguredLimit() {
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        try {
            src.offerMessage(ByteBuf.wrap("X-Test: va".getBytes(StandardCharsets.US_ASCII)));
            assertFalse(HttpHeaderStreamingScanner.scan(src, state, Integer.MAX_VALUE, (ctx, line, ns, nl, vs, vl) -> {
                fail("Incomplete line");
                return false;
            }));
            src.offerMessage(ByteBuf.wrap("lue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            assertTrue(HttpHeaderStreamingScanner.scan(src, state, Integer.MAX_VALUE, (ctx, line, ns, nl, vs, vl) -> {
                assertEquals("X-Test", line.getString(ns, nl, StandardCharsets.US_ASCII));
                assertEquals("value", line.getString(vs, vl, StandardCharsets.US_ASCII));
                return false;
            }));
        } finally {
            src.clearAndRelease();
            state.releaseAndReset();
        }
        assertNull(state.headerLineScratch);
        assertEquals(0, state.headerLineLength);
    }

    private static ByteBuf input(byte[] bytes, int offset, int length, boolean direct) {
        ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
        buffer.put(bytes, offset, length).flip();
        return ByteBuf.wrap(buffer).asReadOnly();
    }

    @Test
    public void completeTerminatorsPreserveFollowingBytesAndOwnership() {
        for (String lineEnd : new String[] { "\n", "\r\n" }) {
            for (String terminator : new String[] { "\n", "\r\n" }) {
                for (String following : new String[] { "", "body", "X-Next: next\r\n\r\n" }) {
                    for (int storage = 0; storage < 3; storage++) {
                        for (boolean accept : new boolean[] { false, true }) {
                            long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
                            long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
                            String headers = "X: value" + lineEnd + terminator;
                            byte[] wire = ("skip" + headers + following).getBytes(StandardCharsets.US_ASCII);
                            ByteBuf input = storage == 0 ? ByteBuf.wrap(wire) : input(wire, 0, wire.length, storage == 2);
                            ByteBuf next = ByteBuf.wrap(new byte[] { 42 });
                            ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
                            HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
                            List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
                            input.skipReadableBytes(4);
                            src.offerMessage(input);
                            src.offerMessage(next);
                            try {
                                int[] calls = { 0 };
                                assertTrue(HttpHeaderStreamingScanner.scan(src, state, headers.length(), (ctx, line, ns, nl, vs, vl) -> {
                                    calls[0]++;
                                    assertEquals("X", line.getString(ns, nl, StandardCharsets.US_ASCII));
                                    assertEquals("value", line.getString(vs, vl, StandardCharsets.US_ASCII));
                                    if (accept) {
                                        entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                                    }
                                    return accept;
                                }));
                                assertEquals(1, calls[0]);
                                assertEquals(headers.length(), state.headerBytes);
                                if (following.isEmpty()) {
                                    assertSame(next, src.peekMessage());
                                    assertEquals(1, src.queueSize());
                                } else {
                                    assertSame(input, src.peekMessage());
                                    assertEquals(2, src.queueSize());
                                    assertEquals(following, input.getString(0, input.readableBytes(), StandardCharsets.US_ASCII));
                                }
                                src.clearAndRelease();
                                state.releaseAndReset();
                                if (accept) {
                                    assertEquals("X", entries.get(0).getName());
                                    assertEquals("value", entries.get(0).getValue());
                                }
                            } finally {
                                entries.forEach(DefaultHttpHeaderEntry::release);
                                src.clearAndRelease();
                                state.releaseAndReset();
                            }
                            assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
                            assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void overLimitTerminatorIsCountedButNotConsumed() {
        for (String lineEnd : new String[] { "\n", "\r\n" }) {
            for (String terminator : new String[] { "\n", "\r\n" }) {
                for (int storage = 0; storage < 3; storage++) {
                    long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
                    long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
                    String headers = "X: value" + lineEnd + terminator;
                    byte[] wire = (headers + "body").getBytes(StandardCharsets.US_ASCII);
                    ByteBuf input = storage == 0 ? ByteBuf.wrap(wire) : input(wire, 0, wire.length, storage == 2);
                    ProtoQueue<ByteBuf> src = new ProtoQueue<>(8);
                    HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
                    List<DefaultHttpHeaderEntry> entries = new ArrayList<>();
                    src.offerMessage(input);
                    try {
                        try {
                            HttpHeaderStreamingScanner.scan(src, state, headers.length() - 1, (ctx, line, ns, nl, vs, vl) -> {
                                entries.add(DefaultHttpHeaderEntry.newOwnedEntry(line, ns, nl, vs, vl));
                                return true;
                            });
                            fail("Expected header size limit failure");
                        } catch (HttpHeaderTooLargeException expected) {
                            assertEquals(headers.length(), state.headerBytes);
                        }
                        assertEquals(1, entries.size());
                        assertSame(input, src.peekMessage());
                        assertEquals(terminator + "body", input.getString(0, input.readableBytes(), StandardCharsets.US_ASCII));
                        src.clearAndRelease();
                        state.releaseAndReset();
                        assertEquals("X", entries.get(0).getName());
                        assertEquals("value", entries.get(0).getValue());
                    } finally {
                        entries.forEach(DefaultHttpHeaderEntry::release);
                        src.clearAndRelease();
                        state.releaseAndReset();
                    }
                    assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
                    assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
                }
            }
        }
    }
}
