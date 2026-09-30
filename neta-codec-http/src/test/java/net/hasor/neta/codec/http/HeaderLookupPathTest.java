/*
 * Copyright 2015-2022 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderLookupPathTest {
    @Test
    public void firstValuePreservesOrderAcrossRawResolvedAndExposedEntries() {
        for (int representation = 0; representation < 4; representation++) {
            byte[] bytes = "Alpha: one\r\nAlpha: two\r\nBeta: three\r\n".getBytes(StandardCharsets.US_ASCII);
            ByteBuf source = ByteBuf.wrap(bytes);
            HeaderEntryStore store = new HeaderEntryStore();
            DefaultHttpHeaders headers = new DefaultHttpHeaders(store, true);
            try {
                store.addDecoded(source, 0, 5, 7, 3);
                store.addDecoded(source, 12, 5, 19, 3);
                store.addDecoded(source, 24, 4, 30, 5);
                if (representation == 1) {
                    store.getName(0);
                } else if (representation == 2) {
                    store.get(1);
                } else if (representation == 3) {
                    headers.addHeader("ALPHA", "four");
                    store.get(0);
                }
                assertEquals("one", headers.getString("ALPHA"));
                assertSame(headers.getString("Alpha"), headers.getString("alpha"));
                assertEquals("three", headers.getString("bETA"));
                assertNull(headers.getString("missing"));
                assertNull(headers.getString(null));
                assertNull(headers.getString(""));

                bytes[0] = 'Z';
                if (representation == 1) {
                    assertEquals("one", headers.getString("ALPHA"));
                    assertNull(headers.getString("zlpha"));
                } else {
                    assertEquals("two", headers.getString("ALPHA"));
                    assertEquals("one", headers.getString("zlpha"));
                }
            } finally {
                headers.release();
                assertEquals(1, source.refCnt());
                source.release();
            }
        }
    }

    @Test
    public void reusedStoreDoesNotReturnPreviouslyResolvedValues() {
        HeaderEntryStore store = new HeaderEntryStore();
        DefaultHttpHeaders headers = new DefaultHttpHeaders(store, true);
        try {
            assertNull(headers.getString("A"));
            for (int i = 0; i < 3; i++) {
                headers.addHeader("A", Integer.toString(i));
                assertEquals(Integer.toString(i), headers.getString("a"));
                headers.release();
                assertNull(headers.getString("A"));
            }
        } finally {
            headers.release();
        }
    }
}
