/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.StringView;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DefaultHttpHeaderEntryLifecycleTest {
    @Test
    public void materializingOneFieldPreservesTheOtherFieldAndOwnedReference() {
        for (boolean valueFirst : new boolean[] { true, false }) {
            byte[] bytes = "Name:value".getBytes(StandardCharsets.US_ASCII);
            ByteBuf source = ByteBuf.wrap(bytes);
            DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 5);
            try {
                String first = valueFirst ? entry.getValue() : entry.getName();
                assertSame(first, valueFirst ? entry.getValue() : entry.getName());
                assertEquals(1, source.refCnt());
                bytes[0] = 'G';
                bytes[5] = 'V';
                assertEquals(valueFirst ? "Game" : "Value", valueFirst ? entry.getName() : entry.getValue());
                assertEquals(0, source.refCnt());
                assertEquals(valueFirst ? "Game" : "Name", entry.getName());
                assertEquals(valueFirst ? "value" : "Value", entry.getValue());
            } finally {
                entry.release();
            }
        }
    }

    @Test
    public void repeatedReadsReleaseStringViewsOnlyOnce() {
        ByteBuf name = ByteBuf.wrap("Name".getBytes(StandardCharsets.US_ASCII));
        ByteBuf value = ByteBuf.wrap("value".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newEntry(StringView.request(name, 0, 4), StringView.request(value, 0, 5));
        try {
            assertEquals(2, name.refCnt());
            assertEquals(2, value.refCnt());
            assertSame(entry.getValue(), entry.getValue());
            assertEquals(1, value.refCnt());
            assertEquals(2, name.refCnt());
            assertSame(entry.getName(), entry.getName());
            assertEquals(1, name.refCnt());
        } finally {
            entry.release();
            name.release();
            value.release();
        }
    }

    @Test
    public void lookupPreservesFirstDuplicateAndDoesNotMaterializeNames() {
        byte[] bytes = "Name:first".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeaderEntry(DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 5));
        headers.addHeader("name", "second");
        try {
            assertEquals("first", headers.getString("NAME"));
            assertEquals(1, source.refCnt());
            bytes[0] = 'G';
            assertEquals("second", headers.getString("name"));
            assertEquals("first", headers.getString("game"));
            assertNull(headers.getString(null));
            assertNull(headers.getString("missing"));
            headers.removeHeader("game");
            assertEquals(0, source.refCnt());
            headers.setHeader("name", "replacement");
            assertEquals("replacement", headers.getString("NAME"));
        } finally {
            headers.release();
        }
    }

    @Test
    public void emptyValueKeepsSourceUntilNameIsMaterialized() {
        ByteBuf source = ByteBuf.wrap("Empty:".getBytes(StandardCharsets.US_ASCII));
        DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 5, 6, 0);
        try {
            assertEquals("", entry.getValue());
            assertEquals("", entry.getValue());
            assertEquals(1, source.refCnt());
            assertEquals("Empty", entry.getName());
            assertEquals(0, source.refCnt());
            assertEquals("", entry.getValue());
        } finally {
            entry.release();
        }
    }

    @Test
    public void constructorsStartWithOneReference() {
        for (DefaultHttpHeaderEntry entry : new DefaultHttpHeaderEntry[] { new DefaultHttpHeaderEntry("Name", "value"), new DefaultHttpHeaderEntry(new StringBuilder("Name"), new StringBuilder("value")) }) {
            assertEquals(1, entry.refCnt());
            entry.retain();
            assertFalse(entry.release());
            assertEquals("Name", entry.getName());
            assertEquals("value", entry.getValue());
            assertTrue(entry.release());
            assertEquals(0, entry.refCnt());
        }
    }

    @Test
    public void pooledEntriesAlternateTextAndOwnedInputWithoutStaleState() {
        for (int i = 0; i < 100; i++) {
            DefaultHttpHeaderEntry text = DefaultHttpHeaderEntry.newEntry("Before", "text" + i);
            assertEquals(1, text.refCnt());
            assertEquals("text" + i, text.getValue());
            assertTrue(text.release());

            ByteBuf source = ByteBuf.wrap("Name:value".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaderEntry owned = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 5);
            assertEquals(1, owned.refCnt());
            owned.retain();
            assertFalse(owned.release());
            assertFalse(source.isFree());
            assertEquals("Name", owned.getName());
            assertEquals("value", owned.getValue());
            assertTrue(source.isFree());
            assertTrue(owned.release());

            ByteBuf emptySource = ByteBuf.wrap("Empty:".getBytes(StandardCharsets.US_ASCII));
            DefaultHttpHeaderEntry empty = DefaultHttpHeaderEntry.newOwnedEntry(emptySource, 0, 5, 6, 0);
            assertEquals(1, empty.refCnt());
            assertEquals("", empty.getValue());
            assertTrue(empty.release());
            assertTrue(emptySource.isFree());
        }
    }

    @Test
    public void everyStandardValuePreservesOffsetsAndOwnership() throws Exception {
        for (Field field : HttpHeaderValues.class.getFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String value = (String) field.get(null);
            for (ByteOrder order : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                ByteBuf source = ByteBuf.wrap(("ignoredX:" + value + "tail").getBytes(StandardCharsets.US_ASCII));
                source.order(order);
                source.skipReadableBytes(7);
                DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 1, 2, value.length());
                try {
                    assertEquals(value, entry.getValue());
                    assertSame(entry.getValue(), entry.getValue());
                    assertEquals(1, source.refCnt());
                    assertEquals("X", entry.getName());
                    assertEquals(0, source.refCnt());
                } finally {
                    entry.release();
                }
            }
        }
    }

    @Test
    public void standardValueIsReadAtMaterializationTimeWithoutCaseNormalization() {
        byte[] bytes = "Name:keep-alive".getBytes(StandardCharsets.US_ASCII);
        ByteBuf source = ByteBuf.wrap(bytes);
        DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, 10);
        try {
            bytes[5] = 'K';
            assertEquals("Keep-alive", entry.getValue());
            bytes[5] = 'x';
            bytes[0] = 'G';
            assertEquals("Keep-alive", entry.getValue());
            assertEquals("Game", entry.getName());
            assertTrue(source.isFree());
        } finally {
            entry.release();
        }
    }

    @Test
    public void similarAndNonAsciiValuesAreNotReplacedByStandardValues() {
        String[] values = { "keep-alivx", "KEEP-ALIVE", "text/HTML", "application/jsom", "gzip, deflate", "application/json; charset=utf-8", "ke\u00e9p-alive", "" };
        for (String value : values) {
            byte[] bytes = ("Name:" + value).getBytes(StandardCharsets.UTF_8);
            ByteBuf source = ByteBuf.wrap(bytes);
            DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newOwnedEntry(source, 0, 4, 5, bytes.length - 5);
            try {
                assertEquals(value, entry.getValue());
                assertEquals("Name", entry.getName());
                assertTrue(source.isFree());
            } finally {
                entry.release();
            }
        }
    }
}
