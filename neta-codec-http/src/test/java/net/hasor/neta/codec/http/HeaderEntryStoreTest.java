/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderEntryStoreTest {
    @Test
    public void releaseCoversEveryMaterializedEntryInsertionPath() {
        for (int operation = 0; operation < 6; operation++) {
            ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            DefaultHttpHeaderEntry entry = new DefaultHttpHeaderEntry("Owned", "value");
            try {
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                HeaderEntryStore store = (HeaderEntryStore) headers.headerEntries();
                switch (operation) {
                    case 0 -> headers.addHeaderEntry(entry);
                    case 1 -> store.add(1, entry);
                    case 2 -> store.set(0, entry).release();
                    case 3 -> store.addAll(Arrays.asList(null, entry));
                    case 4 -> store.addAll(1, List.of(entry));
                    case 5 -> {
                        HeaderEntryStore other = new HeaderEntryStore(List.of(entry));
                        store.addAll(other);
                        other.clear();
                    }
                }
                headers.release();
                assertEquals(0, entry.refCnt());
                assertEquals(1, source.refCnt());
            } finally {
                headers.release();
                if (entry.refCnt() > 0)
                    entry.release();
                source.release();
            }
        }
    }

    @Test
    public void transferredExposedEntriesKeepTheirIndependentLifetime() {
        for (boolean nonemptyTarget : new boolean[] { false, true }) {
            ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaders original = new DefaultHttpHeaders();
            DefaultHttpHeaders target = new DefaultHttpHeaders();
            DefaultHttpHeaderEntry entry = null;
            try {
                original.addDecodedHeader(source, 0, 4, 5, 2);
                original.addDecodedHeader(source, 0, 4, 5, 2);
                entry = original.headerEntries().get(0).retain();
                if (nonemptyTarget)
                    target.addDecodedHeader(source, 0, 4, 5, 2);
                target.transferHeaders(original);
                original.release();
                assertEquals(2, entry.refCnt());
                target.release();
                assertEquals(1, entry.refCnt());
                assertEquals(2, source.refCnt());
                assertEquals("Name", entry.getName());
                assertEquals("42", entry.getValue());
                assertEquals(1, source.refCnt());
            } finally {
                original.release();
                target.release();
                if (entry != null)
                    entry.release();
                source.release();
            }
        }
    }

    @Test
    public void recycledStoresAndRawTransfersStillReleaseExistingEntries() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        for (int cycle = 0; cycle < 4; cycle++) {
            ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaders raw = new DefaultHttpHeaders();
            DefaultHttpHeaderEntry entry = new DefaultHttpHeaderEntry("Owned", "value");
            try {
                headers.addHeaderEntry(entry);
                raw.addDecodedHeader(source, 0, 4, 5, 2);
                headers.transferHeaders(raw);
                raw.release();
                assertEquals("42", headers.getString("Name"));
                headers.release();
                assertEquals(0, entry.refCnt());
                assertEquals(1, source.refCnt());
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                assertEquals("42", headers.getString("Name"));
                assertEquals(2, source.refCnt());
                headers.release();
                assertEquals(1, source.refCnt());
            } finally {
                headers.release();
                raw.release();
                if (entry.refCnt() > 0)
                    entry.release();
                source.release();
            }
        }
    }

    @Test
    public void valueLookupPreservesDuplicatesCachingAndEmptyValuesAcrossBuffers() {
        for (int kind = 0; kind < 3; kind++) {
            byte[] bytes = "Name:one|NAME:two|Empty:".getBytes(StandardCharsets.US_ASCII);
            ByteBuffer buffer = kind == 2 ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
            buffer.put(bytes).flip();
            ByteBuf source = kind == 0 ? ByteBuf.wrap(bytes) : ByteBuf.wrap(buffer.asReadOnlyBuffer());
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            try {
                assertNull(headers.getString("Name"));
                headers.addHeader("Other", "before");
                headers.addDecodedHeader(source, 0, 4, 5, 3);
                headers.addDecodedHeader(source, 9, 4, 14, 3);
                headers.addDecodedHeader(source, 18, 5, 24, 0);
                assertNull(headers.getString(null));
                assertNull(headers.getString("missing"));
                assertEquals("before", headers.getString("other"));
                String first = headers.getString("nAmE");
                assertEquals("one", first);
                assertSame(first, headers.getString("NAME"));
                assertEquals("", headers.getString("EMPTY"));
                assertEquals(List.of("one", "two"), headers.getValues("name"));
                assertEquals(2, source.refCnt());
                headers.headerNames();
                assertEquals(1, source.refCnt());
                assertSame(first, headers.getString("name"));
                headers.headerEntries().get(1);
                assertSame(first, headers.getString("name"));
            } finally {
                headers.release();
                assertEquals(1, source.refCnt());
                source.release();
            }
        }
    }

    @Test
    public void valueLookupKeepsUnresolvedNamesLiveAndResolvedValuesStable() {
        byte[] bytes = "Name:one|NAME:two".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addDecodedHeader(source, 0, 4, 5, 3);
            headers.addDecodedHeader(source, 9, 4, 14, 3);
            String first = headers.getString("name");
            bytes[0] = 'G';
            bytes[5] = 'x';
            assertEquals("two", headers.getString("name"));
            assertSame(first, headers.getString("game"));
            headers.headerNames();
            assertEquals(1, source.refCnt());
            bytes[0] = 'F';
            assertSame(first, headers.getString("GAME"));
            assertNull(headers.getString("fame"));
        } finally {
            headers.release();
            source.release();
        }
    }

    @Test
    public void rawLookupKeepsFirstLiveNameWhenAnotherRowMaterializes() {
        byte[] bytes = "Small:0|Name:one|NAME:two|Empty:".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addDecodedHeader(source, 0, 5, 6, 1);
            headers.addDecodedHeader(source, 8, 4, 13, 3);
            headers.addDecodedHeader(source, 17, 4, 22, 3);
            headers.addDecodedHeader(source, 26, 5, 32, 0);
            HeaderEntryStore store = (HeaderEntryStore) headers.headerEntries();
            assertEquals(1, store.findFirstIndex("NAME"));
            String first = headers.getString("name");
            assertEquals("one", first);
            assertEquals("", headers.getString("empty"));
            bytes[8] = 'G';
            bytes[13] = 'x';
            for (boolean materialize : new boolean[] { false, true }) {
                if (materialize)
                    store.get(0);
                assertEquals(2, store.findFirstIndex("name"));
                assertEquals(1, store.findFirstIndex("game"));
                assertEquals("two", headers.getString("name"));
                assertSame(first, headers.getString("GAME"));
                assertNull(headers.getString("absent"));
            }
            headers.headerNames();
            bytes[8] = 'F';
            assertTrue(headers.containsHeader("Game"));
            assertFalse(headers.containsHeader("Fame"));
            assertSame(first, headers.getString("game"));
            assertEquals("0", headers.getString("small"));
            assertEquals(1, source.refCnt());
        } finally {
            headers.release();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void transferredRawAndMixedStoresKeepLiveLookupOrder() {
        for (int targetKind = 0; targetKind < 3; targetKind++) {
            for (boolean materialize : new boolean[] { false, true }) {
                byte[] bytes = "Name:one|NAME:two".getBytes(StandardCharsets.US_ASCII);
                ByteBuf source = ByteBuf.wrap(bytes);
                ByteBuf prefix = ByteBuf.wrap("Other:0".getBytes(StandardCharsets.US_ASCII));
                DefaultHttpHeaders original = new DefaultHttpHeaders();
                DefaultHttpHeaders target = new DefaultHttpHeaders();
                try {
                    original.addDecodedHeader(source, 0, 4, 5, 3);
                    original.addDecodedHeader(source, 9, 4, 14, 3);
                    if (materialize)
                        original.headerEntries().get(1);
                    if (targetKind == 1)
                        target.addDecodedHeader(prefix, 0, 5, 6, 1);
                    else if (targetKind == 2)
                        target.addHeader("Other", "0");
                    target.transferHeaders(original);
                    assertEquals(0, original.headerSize());
                    original.release();
                    HeaderEntryStore store = (HeaderEntryStore) target.headerEntries();
                    int first = targetKind == 0 ? 0 : 1;
                    assertEquals(first, store.findFirstIndex("name"));
                    String value = target.getString("name");
                    assertEquals("one", value);
                    bytes[0] = 'G';
                    assertEquals(first + 1, store.findFirstIndex("name"));
                    assertEquals(first, store.findFirstIndex("game"));
                    assertEquals("two", target.getString("NAME"));
                    assertSame(value, target.getString("game"));
                    if (targetKind != 0)
                        assertEquals("0", target.getString("other"));
                } finally {
                    original.release();
                    target.release();
                    assertEquals(1, source.refCnt());
                    assertEquals(1, prefix.refCnt());
                    source.release();
                    prefix.release();
                }
            }
        }
    }

    @Test
    public void lookupSurvivesEntryRemovalAndRawStorageReuse() {
        ByteBuf source = ByteBuf.wrap("Name:42".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                HeaderEntryStore store = (HeaderEntryStore) headers.headerEntries();
                assertEquals(0, store.findFirstIndex("NAME"));
                assertEquals("42", headers.getString("name"));
                store.remove(0).release();
                assertEquals(0, store.findFirstIndex("NAME"));
                assertEquals("42", headers.getString("name"));
                assertEquals(2, source.refCnt());
                headers.release();
                assertEquals(-1, store.findFirstIndex("name"));
                assertNull(store.findFirstValue("name"));
                assertEquals(1, source.refCnt());
            }
        } finally {
            headers.release();
            source.release();
        }
    }

    @Test
    public void lookupPreservesNullAndDeferredRangeFailureSemantics() {
        HeaderEntryStore store = new HeaderEntryStore();
        ByteBuf source = ByteBuf.wrap(new byte[] { 'a' });
        try {
            store.addDecoded(source, 0, 3, 0, 0);
            assertEquals(-1, store.findFirstIndex(null));
            assertNull(store.findFirstValue(null));
            assertEquals(-1, store.findFirstIndex("different-length"));
            assertNull(store.findFirstValue("different-length"));
            assertThrows(IndexOutOfBoundsException.class, () -> store.findFirstIndex("abc"));
            assertThrows(IndexOutOfBoundsException.class, () -> store.findFirstValue("abc"));
            store.clear();
            store.add(null);
            assertEquals(-1, store.findFirstIndex(null));
            assertNull(store.findFirstValue(null));
            assertThrows(NullPointerException.class, () -> store.findFirstIndex("name"));
            assertThrows(NullPointerException.class, () -> store.findFirstValue("name"));
        } finally {
            store.clear();
            assertEquals(1, source.refCnt());
            source.release();
        }
    }

    @Test
    public void cachedNameLookupUsesDecodedCharacterLength() {
        byte[] bytes = { (byte) 0xc3, (byte) 0xa9, ':', '7' };
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addDecodedHeader(source, 0, 2, 3, 1);
            String name = new String(bytes, 0, 2, Charset.defaultCharset());
            assertEquals(Collections.singleton(name), headers.headerNames());
            assertTrue(headers.containsHeader(name));
            assertEquals(7, headers.getLong(name, -1));
            assertEquals("7", headers.getString(name));
            assertEquals(1, source.refCnt());
            headers.addHeader("Other", "0");
            assertTrue(headers.containsHeader(name));
            assertEquals("7", headers.getString(name));
        } finally {
            headers.release();
            source.release();
        }
    }

    @Test
    public void transferredResolvedNamesKeepCharacterLengthAndReleasedSources() {
        for (int targetKind = 0; targetKind < 3; targetKind++) {
            byte[] bytes = { (byte) 0xc3, (byte) 0xa9, ':', '7' };
            ByteBuf source = ByteBuf.wrap(bytes);
            ByteBuf prefix = ByteBuf.wrap("Other:0".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaders original = new DefaultHttpHeaders();
            DefaultHttpHeaders target = new DefaultHttpHeaders();
            try {
                original.addDecodedHeader(source, 0, 2, 3, 1);
                String name = new String(bytes, 0, 2, Charset.defaultCharset());
                assertEquals(Collections.singleton(name), original.headerNames());
                assertEquals("7", original.getString(name));
                assertEquals(1, source.refCnt());
                if (targetKind == 1)
                    target.addDecodedHeader(prefix, 0, 5, 6, 1);
                else if (targetKind == 2)
                    target.addHeader("Other", "0");
                target.transferHeaders(original);
                original.release();
                source.release();
                HeaderEntryStore store = (HeaderEntryStore) target.headerEntries();
                assertEquals(targetKind == 0 ? 0 : 1, store.findFirstIndex(name));
                assertEquals("7", target.getString(name));
                assertEquals(7, target.getLong(name, -1));
                if (targetKind != 0)
                    assertEquals("0", target.getString("other"));
            } finally {
                original.release();
                target.release();
                if (!source.isFree())
                    source.release();
                assertEquals(1, prefix.refCnt());
                prefix.release();
            }
        }
    }

    @Test
    public void partiallyResolvedRawStorePreservesLiveNamesAcrossReuse() {
        HeaderEntryStore store = new HeaderEntryStore();
        for (int cycle = 0; cycle < 4; cycle++) {
            byte[] bytes = "Name:one|Other:two".getBytes(StandardCharsets.US_ASCII);
            ByteBuf source = ByteBuf.wrap(bytes);
            try {
                store.addDecoded(source, 0, 4, 5, 3);
                store.addDecoded(source, 9, 5, 15, 3);
                assertEquals("Name", store.getName(0));
                assertEquals("one", store.getValue(0));
                bytes[0] = 'G';
                bytes[9] = 'A';
                assertEquals(0, store.findFirstIndex("name"));
                assertEquals(-1, store.findFirstIndex("game"));
                assertEquals(-1, store.findFirstIndex("other"));
                assertEquals(1, store.findFirstIndex("ather"));
                assertEquals("one", store.findFirstValue("name"));
                assertEquals("two", store.findFirstValue("ATHER"));
                assertEquals(2, source.refCnt());
            } finally {
                store.clear();
                assertEquals(1, source.refCnt());
                source.release();
            }
        }
    }

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
