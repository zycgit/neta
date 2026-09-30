/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpHeaderOwnershipTest {
    @Test
    public void decodedLookupPreservesMutationAndCachedStringIdentity() {
        byte[] bytes = "Name:42".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 4, 5, 2));
            assertEquals(42, headers.getLong("NAME", -1));
            bytes[0] = 'G';
            bytes[5] = '5';
            assertFalse(headers.containsHeader("name"));
            String value = headers.getString("GAME");
            assertEquals("52", value);
            bytes[5] = '9';
            assertSame(value, headers.getString("game"));
            assertEquals(52, headers.getLong("game", -1));
            assertEquals(2, source.refCnt());
            String name = headers.headerNames().iterator().next();
            assertEquals("Game", name);
            assertEquals(1, source.refCnt());
            bytes[0] = 'T';
            DefaultHttpHeaderEntry entry = headers.findFirstEntry("game");
            assertSame(name, entry.getName());
            assertSame(value, entry.getValue());
            assertSame(entry, headers.headerEntries().get(0));
            assertFalse(headers.containsHeader("tame"));
        } finally {
            headers.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void retainedEntryOutlivesContainerAndItsStorageReuse() {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        DefaultHttpHeaders shared = new DefaultHttpHeaders();
        DefaultHttpHeaderEntry retained = null;
        try {
            headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 4, 5, 2));
            retained = headers.findFirstEntry("name").retain();
            shared.addHeaderEntry(retained.retain());
            headers.release();
            for (int i = 0; i < 80; i++) {
                headers.addHeader("Other-" + i, "unrelated");
            }
            assertEquals(2, retained.refCnt());
            assertEquals(2, source.refCnt());
            shared.release();
            assertEquals(1, retained.refCnt());
            assertEquals("42", retained.getValue());
            assertEquals("Name", retained.getName());
            assertEquals(1, source.refCnt());
        } finally {
            headers.release();
            shared.release();
            if (retained != null) {
                retained.release();
            }
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void transferAndCopyPreserveMixedEntriesAndReleaseOwnershipOnce() {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaders original = new DefaultHttpHeaders();
        DefaultHttpHeaders transferred = new DefaultHttpHeaders();
        DefaultHttpHeaders copied = new DefaultHttpHeaders();
        try {
            for (int i = 0; i < 129; i++) {
                original.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 4, 5, 2));
                if (i % 3 == 0) {
                    original.addHeader("Plain-" + i, "value");
                }
            }
            assertEquals(130, source.refCnt());
            DefaultHttpHeaderEntry first = original.findFirstEntry("name");
            String firstValue = original.getString("name");
            transferred.addHeader("Prefix", "before");
            transferred.transferHeaders(original);
            assertEquals(0, original.headerSize());
            assertEquals(173, transferred.headerSize());
            assertSame(first, transferred.findFirstEntry("NAME"));
            assertSame(firstValue, transferred.getString("Name"));
            original.release();
            assertEquals(130, source.refCnt());
            copied.appendHeaders(transferred);
            assertEquals(1, source.refCnt());
            assertEquals(Collections.nCopies(129, "42"), copied.getValues("name"));
            transferred.setHeader("NAME", "replacement");
            transferred.removeHeader("Prefix");
            assertEquals(44, transferred.headerSize());
            assertEquals(Collections.singletonList("replacement"), transferred.getValues("name"));
            assertEquals(129, copied.getValues("name").size());
            transferred.release();
            assertEquals("42", copied.getString("name"));
        } finally {
            original.release();
            transferred.release();
            copied.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void rawAndMaterializedRemovalKeepsReturnedEntriesAlive() {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        HeaderEntryStore store = new HeaderEntryStore();
        DefaultHttpHeaderEntry removed = null;
        DefaultHttpHeaderEntry replaced = null;
        DefaultHttpHeaderEntry plain = new DefaultHttpHeaderEntry("Plain", "value");
        try {
            store.add(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 4, 5, 2));
            store.add(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 4, 5, 2));
            store.add(1, plain);
            removed = store.remove(0);
            replaced = store.set(1, plain);
            assertEquals(Arrays.asList(plain, plain), store);
            store.addAll(1, store.subList(0, 2));
            store.clear();
            assertEquals(3, source.refCnt());
            assertEquals(1, removed.refCnt());
            assertEquals(1, replaced.refCnt());
            assertEquals("42", removed.getValue());
            assertEquals("Name", replaced.getName());
            assertEquals(1, plain.refCnt());
        } finally {
            store.clear();
            if (removed != null) {
                removed.release();
            }
            if (replaced != null) {
                replaced.release();
            }
            plain.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void emptyValueAndSelfAppendPreserveIndependentCopies() {
        ByteBuf source = ByteBuf.wrap("Empty:".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source.retain(), 0, 5, 6, 0));
            assertEquals("", headers.getString("empty"));
            assertEquals(-7, headers.getLong("empty", -7));
            assertEquals(2, source.refCnt());
            headers.transferHeaders(headers);
            assertEquals(Arrays.asList("", ""), headers.getValues("EMPTY"));
            assertEquals(1, source.refCnt());
            assertNotSame(headers.headerEntries().get(0), headers.headerEntries().get(1));
            headers.clearHeader();
            headers.addHeader("Reused", "value");
            assertEquals(Collections.singleton("Reused"), headers.headerNames());
        } finally {
            headers.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }
}
