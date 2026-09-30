/*
 * Copyright 2015-2022 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.neta.codec.http;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpHeaderNameComparisonTest {
    @Test
    public void rawNamesStayLiveAcrossLengthsBuffersAndMixedEntries() {
        for (int length : new int[] { 1, 3, 4, 7, 8, 17, 31, 63, 64, 65, 127, 257 }) {
            for (int kind = 0; kind < 3; kind++) {
                String name = "Ab-C_9".repeat((length + 5) / 6).substring(0, length);
                byte[] bytes = ("xx" + name + ":value").getBytes(StandardCharsets.US_ASCII);
                ByteBuffer buffer = kind == 2 ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.wrap(bytes);
                if (kind == 2) {
                    buffer.put(bytes).flip();
                }
                ByteBuf source = kind == 0 ? ByteBuf.wrap(bytes) : ByteBuf.wrap(buffer.asReadOnlyBuffer());
                HeaderEntryStore store = new HeaderEntryStore();
                DefaultHttpHeaderEntry entry = new DefaultHttpHeaderEntry("Other", "0");
                try {
                    store.addDecoded(source, 2, length, length + 3, 5);
                    String lower = name.toLowerCase(Locale.ROOT);
                    assertEquals(0, store.findFirstIndex(lower));
                    assertEquals("value", store.findFirstValue(lower));
                    buffer.put(2, (byte) 'X');
                    String changed = "x" + lower.substring(1);
                    for (int mixed = 0; mixed < 2; mixed++) {
                        if (mixed == 1) {
                            store.add(entry);
                        }
                        assertEquals(-1, store.findFirstIndex(lower));
                        assertNull(store.findFirstValue(lower));
                        assertEquals("value", store.findFirstValue(changed));
                        assertTrue(store.matchesName(0, changed));
                        assertFalse(store.matchesName(0, changed + "x"));
                    }
                    assertEquals("X" + name.substring(1), store.getName(0));
                    assertEquals(1, source.refCnt());
                    buffer.put(2, (byte) 'Y');
                    assertEquals("value", store.findFirstValue(changed));
                    assertNull(store.findFirstValue("y" + lower.substring(1)));
                } finally {
                    store.clear();
                    entry.release();
                    assertEquals(1, source.refCnt());
                    source.release();
                }
            }
        }
    }

    @Test
    public void rawNameCaseFoldingChangesOnlyAsciiLetters() {
        byte[] bytes = { 0, ':', '7' };
        ByteBuf source = ByteBuf.wrap(bytes);
        HeaderEntryStore store = new HeaderEntryStore();
        try {
            store.addDecoded(source, 0, 1, 2, 1);
            for (int value = 0; value < 256; value++) {
                bytes[0] = (byte) value;
                for (int query = 0; query < 512; query++) {
                    int left = value >= 'A' && value <= 'Z' ? value + 32 : value;
                    int right = query >= 'A' && query <= 'Z' ? query + 32 : query;
                    String name = String.valueOf((char) query);
                    assertEquals(left == right ? 0 : -1, store.findFirstIndex(name));
                    assertEquals(left == right, store.matchesName(0, name));
                }
            }
        } finally {
            store.clear();
            source.release();
        }
    }
}
