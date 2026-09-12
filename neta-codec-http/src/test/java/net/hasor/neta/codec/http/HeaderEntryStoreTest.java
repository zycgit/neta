/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderEntryStoreTest {
    @Test
    public void listOperationsMatchArrayListAcrossStorageBoundaries() {
        DefaultHttpHeaderEntry[] pool = new DefaultHttpHeaderEntry[17];
        for (int i = 1; i < pool.length; i++) {
            pool[i] = new DefaultHttpHeaderEntry("Name-" + i, "value-" + i);
        }
        try {
            for (int capacity : new int[] { 0, 1, 4, 5, 12, 64 }) {
                HeaderEntryStore actual = new HeaderEntryStore(capacity);
                List<DefaultHttpHeaderEntry> expected = new ArrayList<>(capacity);
                Random random = new Random(83 + capacity);
                for (int step = 0; step < 4000; step++) {
                    DefaultHttpHeaderEntry entry = pool[random.nextInt(pool.length)];
                    int index = random.nextInt(actual.size() + 1);
                    switch (random.nextInt(10)) {
                        case 0 -> assertEquals(expected.add(entry), actual.add(entry));
                        case 1 -> {
                            expected.add(index, entry);
                            actual.add(index, entry);
                        }
                        case 2 -> {
                            if (index < actual.size()) {
                                assertSame(expected.set(index, entry), actual.set(index, entry));
                            }
                        }
                        case 3 -> {
                            if (index < actual.size()) {
                                assertSame(expected.remove(index), actual.remove(index));
                            }
                        }
                        case 4 -> assertEquals(expected.remove(entry), actual.remove(entry));
                        case 5 -> {
                            List<DefaultHttpHeaderEntry> values = Arrays.asList(entry, null, pool[1]);
                            assertEquals(expected.addAll(index, values), actual.addAll(index, values));
                        }
                        case 6 -> {
                            HeaderEntryStore source = new HeaderEntryStore(Arrays.asList(entry, pool[2]));
                            assertEquals(expected.addAll(source), actual.addAll(source));
                        }
                        case 7 -> assertEquals(expected.addAll(expected), actual.addAll(actual));
                        case 8 -> {
                            int end = index + random.nextInt(actual.size() - index + 1);
                            expected.subList(index, end).clear();
                            actual.subList(index, end).clear();
                        }
                        case 9 -> actual.ensureAppendCapacity(random.nextInt(40));
                    }
                    assertEquals(expected, actual);
                    assertEquals(expected.hashCode(), actual.hashCode());
                    assertArrayEquals(expected.toArray(), actual.toArray());
                    assertArrayEquals(expected.toArray(new DefaultHttpHeaderEntry[expected.size() + 2]), actual.toArray(new DefaultHttpHeaderEntry[actual.size() + 2]));
                    if (actual.size() > 128) {
                        expected.clear();
                        actual.clear();
                    }
                }
            }
        } finally {
            for (DefaultHttpHeaderEntry entry : pool) {
                if (entry != null) {
                    entry.release();
                }
            }
        }
    }

    @Test
    public void iteratorAndSubListMutationsPreserveListSemantics() {
        DefaultHttpHeaderEntry entry = new DefaultHttpHeaderEntry("Name", "value");
        try {
            HeaderEntryStore store = new HeaderEntryStore(Collections.nCopies(9, entry));
            ListIterator<DefaultHttpHeaderEntry> iterator = store.listIterator(3);
            assertSame(entry, iterator.next());
            iterator.set(null);
            iterator.add(entry);
            assertSame(entry, iterator.previous());
            iterator.remove();
            assertNull(store.get(3));
            assertEquals(9, store.size());
            store.addAll(2, store.subList(3, 7));
            assertEquals(13, store.size());
            assertNull(store.get(2));
            assertSame(entry, store.get(3));
            store.subList(1, 8).removeIf(Objects::isNull);
            assertEquals(11, store.size());
            Iterator<DefaultHttpHeaderEntry> stale = store.iterator();
            store.add(entry);
            assertThrows(ConcurrentModificationException.class, stale::next);
            store.replaceAll(ignored -> entry);
            assertEquals(Collections.nCopies(12, entry), store);
            store.retainAll(Collections.emptyList());
            assertTrue(store.isEmpty());
            assertEquals(1, entry.refCnt());
        } finally {
            entry.release();
        }
    }

    @Test
    public void invalidIndicesAndEmptyCollectionsFollowListContract() {
        HeaderEntryStore store = new HeaderEntryStore();
        assertThrows(IllegalArgumentException.class, () -> new HeaderEntryStore(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> store.set(-1, null));
        assertThrows(IndexOutOfBoundsException.class, () -> store.remove(0));
        assertThrows(IndexOutOfBoundsException.class, () -> store.add(1, null));
        assertThrows(IndexOutOfBoundsException.class, () -> store.addAll(1, Collections.emptyList()));
        assertFalse(store.addAll(Collections.emptyList()));
        assertFalse(store.addAll(new HeaderEntryStore()));
        assertFalse(store.addAll(0, store));
        assertTrue(new HeaderEntryStore((List<DefaultHttpHeaderEntry>) null).isEmpty());
        store.add(null);
        assertNull(store.remove(0));
        store.clear();
        assertTrue(store.isEmpty());
    }

    @Test
    public void lookupObservesMutableNamesAtEveryStoragePosition() {
        for (int position = 0; position < 9; position++) {
            byte[] bytes = "Name:42".getBytes(StandardCharsets.US_ASCII);
            ByteBuf source = ByteBuf.wrap(bytes);
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            try {
                for (int i = 0; i < position; i++) {
                    headers.addHeader("Other-" + i, "0");
                }
                headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 2));
                StringBuilder mutable = new StringBuilder("Name");
                headers.addHeader(mutable, "7");
                assertTrue(headers.containsHeader("NAME"));
                assertEquals(42, headers.getLong("name", -1));
                bytes[0] = 'G';
                assertEquals("7", headers.getString("name"));
                assertEquals("42", headers.getString("game"));
                assertEquals(1, source.refCnt());
                mutable.append("-Changed");
                assertFalse(headers.containsHeader("name"));
                assertEquals("7", headers.getString("NAME-CHANGED"));
                headers.setHeader("game", "99");
                assertEquals(0, source.refCnt());
                assertEquals(99, headers.getLong("GAME", -1));
                headers.removeHeader("name-changed");
                assertNull(headers.getString("name-changed"));
            } finally {
                headers.release();
            }
        }
    }

    @Test
    public void sharingEntriesDoesNotGiveTheStoreOwnership() {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 2);
        HeaderEntryStore store = new HeaderEntryStore(Arrays.asList(entry, entry));
        store.addAll(store);
        store.remove(0);
        store.clear();
        assertEquals(1, entry.refCnt());
        assertEquals(1, source.refCnt());
        DefaultHttpHeaders first = new DefaultHttpHeaders();
        DefaultHttpHeaders second = new DefaultHttpHeaders();
        first.addHeaderEntry(entry);
        entry.retain();
        second.addHeaderEntry(entry);
        try {
            first.release();
            assertEquals(1, entry.refCnt());
            assertEquals("42", second.getString("NAME"));
            assertEquals(1, source.refCnt());
        } finally {
            first.release();
            second.release();
        }
        assertEquals(0, source.refCnt());
    }

    @Test
    public void numericLookupPreservesFirstMatchAndDefaults() {
        for (boolean decoded : new boolean[] { false, true }) {
            for (String value : new String[] { " \t42 ", "invalid", "" }) {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                try {
                    headers.addHeader("Other", "99");
                    if (decoded) {
                        byte[] bytes = ("Count:" + value).getBytes(StandardCharsets.US_ASCII);
                        headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(ByteBuf.wrap(bytes), 0, 5, 6, value.length()));
                    } else {
                        headers.addHeader("Count", value);
                    }
                    headers.addHeader("count", "100");
                    assertEquals(value.contains("42") ? 42L : -7L, headers.getLong("COUNT", -7));
                    assertEquals(-7L, headers.getLong(null, -7));
                    assertEquals(-7L, headers.getLong("missing", -7));
                } finally {
                    headers.release();
                }
            }
        }
    }

    @Test
    public void growsTransfersCopiesAndRemovesDuplicateHeaders() {
        DefaultHttpHeaders source = new DefaultHttpHeaders();
        DefaultHttpHeaders transferred = new DefaultHttpHeaders();
        DefaultHttpHeaders copied = new DefaultHttpHeaders();
        try {
            for (int i = 0; i < 257; i++) {
                source.addHeader("X-" + i, "value-" + i);
            }
            source.addHeader("X-128", "duplicate");
            transferred.transferHeaders(source);
            assertEquals(0, source.headerSize());
            assertEquals(258, transferred.headerSize());
            copied.appendHeaders(transferred);
            transferred.setHeader("X-128", "replacement");
            transferred.removeHeader("X-0");
            transferred.removeHeader("X-256");
            assertEquals(255, transferred.headerSize());
            assertEquals("replacement", transferred.getString("X-128"));
            assertEquals(2, copied.getValues("X-128").size());
            assertEquals("value-128", copied.getString("x-128"));
            assertNull(copied.getString(null));
            assertNull(copied.getString("missing"));
            transferred.clearHeader();
            for (int i = 0; i < 257; i++) {
                assertEquals("value-" + i, copied.getString("X-" + i));
            }
            transferred.addHeader("Again", "reused");
            assertEquals(1, transferred.headerSize());
        } finally {
            source.release();
            transferred.release();
            copied.release();
        }
    }
}
