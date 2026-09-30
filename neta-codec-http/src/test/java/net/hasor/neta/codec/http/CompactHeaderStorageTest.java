/*
 * Copyright 2015-2022 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.neta.codec.http;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompactHeaderStorageTest extends AbstractHttpTest {
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object storage(DefaultHttpHeaders headers) throws Exception {
        return field(headers.headerEntries(), "storage");
    }

    @Test
    public void emptyHeadersDoNotAcquireMetadata() throws Exception {
        for (DefaultHttpHeaders headers : new DefaultHttpHeaders[] { new DefaultHttpHeaders(), new DefaultLastHttpHeaders(), new DefaultTrailerHttpHeaders() }) {
            assertNull(storage(headers));
            headers.release();
            assertNull(storage(headers));
            headers.addHeader("Name", "value");
            assertNotNull(storage(headers));
            headers.release();
            assertNull(storage(headers));
        }
    }

    @Test
    public void mutationOnlyMaterializesMatchingRowsAndRetainedEntriesSurvive() throws Exception {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        DefaultHttpHeaderEntry retained = null;
        try {
            for (int i = 0; i < 4; i++)
                headers.addDecodedHeader(source, 0, 4, 5, 2);
            int[] ranges = (int[]) field(storage(headers), "ranges");
            headers.setHeader("Other", "value");
            headers.removeHeader("missing");
            for (int i = 0; i < 4; i++)
                assertEquals(4, ranges[i * 4 + 1]);
            retained = headers.findFirstEntry("NAME").retain();
            headers.setHeader("Name", "changed");
            assertEquals(2, headers.headerSize());
            assertEquals("changed", headers.getString("name"));
            assertEquals(1, retained.refCnt());
            assertEquals("42", retained.getValue());
            assertEquals("Name", retained.getName());
            assertEquals(1, source.refCnt());
        } finally {
            headers.release();
            if (retained != null)
                retained.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void capacityBoundariesReuseArraysWithoutKeepingPayloads() throws Exception {
        for (int count : new int[] { 4, 64, 65, 129 }) {
            ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            try {
                for (int i = 0; i < count; i++)
                    headers.addDecodedHeader(source, 0, 4, 5, 2);
                Object first = storage(headers);
                Object[] data = (Object[]) field(first, "data");
                headers.release();
                assertEquals(1, source.refCnt());
                for (Object item : data)
                    assertNull(item);
                for (int i = 0; i < count; i++)
                    headers.addDecodedHeader(source, 0, 4, 5, 2);
                assertSame(first, storage(headers));
                assertSame(data, field(storage(headers), "data"));
                assertEquals(count, headers.headerSize());
            } finally {
                headers.release();
                assertEquals(1, source.refCnt());
                source.release();
            }
        }
    }

    @Test
    public void rawRowsTransferCopyAndObserveBackingChanges() {
        byte[] bytes = "Name:42".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders original = new DefaultHttpHeaders();
        DefaultHttpHeaders target = new DefaultHttpHeaders();
        DefaultHttpHeaders copy = new DefaultHttpHeaders();
        try {
            for (int i = 0; i < 129; i++)
                original.addDecodedHeader(source, 0, 4, 5, 2);
            target.addHeader("Other", "before");
            target.transferHeaders(original);
            original.release();
            assertEquals(2, source.refCnt());
            bytes[0] = 'G';
            assertNull(target.getString("Name"));
            assertEquals("42", target.getString("Game"));
            copy.appendHeaders(target);
            assertEquals(1, source.refCnt());
            target.release();
            assertEquals(129, copy.getValues("game").size());
            assertEquals("before", copy.getString("other"));
        } finally {
            original.release();
            target.release();
            copy.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void emptyAndNonemptyTrailersAcrossEverySplitKeepEndMarker() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            autoCloseNeta(neta -> {
                VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                    if (request)
                        ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                    else
                        ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
                for (boolean nonempty : new boolean[] { false, true }) {
                    String wire = (request ? "POST / HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n") + "Transfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n" + (nonempty ? "X-End: yes\r\n" : "") + "\r\n";
                    for (int split = 0; split <= wire.length(); split++) {
                        List<HttpObject> out = new ArrayList<>();
                        try {
                            out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(0, split))));
                            out.addAll(receiveAndIntBound(pipe, ascii(wire.substring(split))));
                            assertTrue(pipe.channelInboundErrors().isEmpty());
                            int ends = 0, trailers = 0;
                            for (HttpObject item : out) {
                                if (item instanceof LastHttpContent)
                                    ends++;
                                if (item instanceof TrailerHttpHeaders headers) {
                                    trailers++;
                                    assertEquals("yes", headers.getString("X-End"));
                                }
                            }
                            assertEquals(1, ends);
                            assertEquals(nonempty ? 1 : 0, trailers);
                        } finally {
                            free(out);
                        }
                    }
                }
            });
        }
    }
}
