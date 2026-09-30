/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpCharSequencesTest {
    @Test
    public void allUtf16CharactersPreserveIdentityAndFoldOnlyAsciiCasePairs() {
        StringBuilder left = new StringBuilder("a");
        StringBuilder right = new StringBuilder("a");
        for (int value = 0; value <= Character.MAX_VALUE; value++) {
            left.setCharAt(0, (char) value);
            right.setCharAt(0, (char) value);
            assertTrue(HttpCharSequences.equalsIgnoreCase(left, right));
            right.setCharAt(0, (char) (value ^ 0x20));
            assertEquals(fold(value) == fold(value ^ 0x20), HttpCharSequences.equalsIgnoreCase(left, right));
        }
    }

    @Test
    public void everyBytePairFoldsOnlyAsciiLettersAtDifferentPositions() {
        byte[] bytes = new byte[12];
        Arrays.fill(bytes, (byte) 'a');
        char[] chars = "aaaaaaaaaaaa".toCharArray();
        ByteBuf source = ByteBuf.wrap(bytes);
        try {
            for (int position = 4; position < 8; position++) {
                for (int left = 0; left < 256; left++) {
                    bytes[position] = (byte) left;
                    String leftText = new String(bytes, StandardCharsets.ISO_8859_1);
                    for (int right = 0; right < 256; right++) {
                        chars[position] = (char) right;
                        String rightText = new String(chars);
                        boolean expected = fold(left) == fold(right);
                        assertEquals(expected, HttpCharSequences.equalsIgnoreCase(source, 0, bytes.length, rightText));
                        assertEquals(expected, HttpCharSequences.equalsIgnoreCase(leftText, rightText));
                        assertEquals(expected, HttpCharSequences.containsIgnoreCase(source, 0, bytes.length, rightText));
                        assertEquals(expected, HttpCharSequences.containsIgnoreCase(leftText, rightText));
                    }
                }
                bytes[position] = 'a';
                chars[position] = 'a';
            }
        } finally {
            source.release();
        }
    }

    @Test
    public void arbitraryLengthsOffsetsOrdersAndCaseMatchScalarComparison() {
        Random random = new Random(8217);
        for (int length = 0; length <= 96; length++) {
            byte[] bytes = new byte[length + 17];
            random.nextBytes(bytes);
            ByteBuf source = ByteBuf.wrap(bytes);
            ByteBuf slice = source.slice(3, length + 10);
            try {
                for (ByteOrder order : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                    slice.order(order);
                    for (int offset = 0; offset < 8; offset++) {
                        char[] chars = new String(bytes, offset + 3, length, StandardCharsets.ISO_8859_1).toCharArray();
                        for (int i = 0; i < length; i++) {
                            if (chars[i] >= 'A' && chars[i] <= 'Z' || chars[i] >= 'a' && chars[i] <= 'z') {
                                chars[i] ^= 32;
                            }
                        }
                        String right = new String(chars);
                        assertTrue(HttpCharSequences.equalsIgnoreCase(slice, offset, length, right));
                        if (length > 0) {
                            chars[random.nextInt(length)] = (char) random.nextInt(65536);
                            right = new String(chars);
                            assertEquals(scalar(slice, offset, length, right), HttpCharSequences.equalsIgnoreCase(slice, offset, length, right));
                        }
                    }
                }
            } finally {
                slice.release();
                source.release();
            }
        }
    }

    @Test
    public void wideCharactersCannotMatchTruncatedBytes() {
        ByteBuf source = ByteBuf.wrap("aaaaaaaa".getBytes(StandardCharsets.US_ASCII));
        try {
            for (int position = 0; position < 8; position++) {
                char[] chars = "aaaaaaaa".toCharArray();
                for (int value : new int[] { 0x141, 0x161, 0x212A, 0xD800, 0xFFFF }) {
                    chars[position] = (char) value;
                    assertFalse(HttpCharSequences.equalsIgnoreCase(source, 0, 8, new String(chars)));
                }
            }
        } finally {
            source.release();
        }
    }

    @Test
    public void nonStringSequencesKeepShortCircuitAndLiveContents() {
        ByteBuf source = ByteBuf.wrap("abcdefgh".getBytes(StandardCharsets.US_ASCII));
        try {
            StringBuilder right = new StringBuilder("ABCDEFGH");
            assertTrue(HttpCharSequences.equalsIgnoreCase(source, 0, 8, right));
            right.setCharAt(0, 'X');
            assertFalse(HttpCharSequences.equalsIgnoreCase(source, 0, 8, right));
            CharSequence firstMismatch = new CharSequence() {
                public int length() {
                    return 8;
                }

                public char charAt(int index) {
                    assertEquals(0, index);
                    return 'X';
                }

                public CharSequence subSequence(int start, int end) {
                    throw new AssertionError();
                }
            };
            assertFalse(HttpCharSequences.equalsIgnoreCase(source, 0, 8, firstMismatch));
        } finally {
            source.release();
        }
    }

    @Test
    public void invalidRangesAndReleasedSourcesKeepScalarBehavior() {
        ByteBuf source = ByteBuf.wrap("abc".getBytes(StandardCharsets.US_ASCII));
        assertFalse(HttpCharSequences.equalsIgnoreCase(source, 0, 4, "xxxx"));
        assertThrows(IndexOutOfBoundsException.class, () -> HttpCharSequences.equalsIgnoreCase(source, 0, 4, "abcd"));
        assertThrows(IllegalArgumentException.class, () -> HttpCharSequences.equalsIgnoreCase(source, -1, 4, "abcd"));
        assertTrue(HttpCharSequences.equalsIgnoreCase(source, -1, 0, ""));
        assertFalse(HttpCharSequences.equalsIgnoreCase(source, 0, 3, null));
        source.release();
        assertThrows(IllegalStateException.class, () -> HttpCharSequences.equalsIgnoreCase(source, 0, 3, "abc"));
    }

    @Test
    public void directSourceAndSlicePreserveCaseAndOrder() {
        ByteBuf source = ByteBufAllocator.DEFAULT.directBuffer(32);
        source.writeBytes("__Generic-Header__".getBytes(StandardCharsets.US_ASCII));
        source.markWriter();
        ByteBuf slice = source.slice(2, 14);
        try {
            for (ByteOrder order : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                slice.order(order);
                assertTrue(HttpCharSequences.equalsIgnoreCase(slice, 0, 14, "generic-header"));
                assertFalse(HttpCharSequences.equalsIgnoreCase(slice, 0, 14, "generic_headeR"));
            }
        } finally {
            slice.release();
            source.release();
        }
    }

    @Test
    public void containmentKeepsOffsetsEmptyNeedlesAndNonAsciiSemantics() {
        String value = "__prefix AbC-Name suffix__";
        ByteBuf source = ByteBuf.wrap(value.getBytes(StandardCharsets.US_ASCII));
        try {
            for (String needle : new String[] { "", "prefix", "ABC-name", "suffix", "__", "missing", "AbC_Name" }) {
                assertEquals(HttpCharSequences.containsIgnoreCase(value.substring(2, value.length() - 2), needle), HttpCharSequences.containsIgnoreCase(source, 2, value.length() - 4, needle));
            }
            assertFalse(HttpCharSequences.equalsIgnoreCase("\u212A", "k"));
            assertFalse(HttpCharSequences.containsIgnoreCase("\u00C1", "\u00E1"));
            assertTrue(HttpCharSequences.equalsIgnoreCase("\u00E1", new StringBuilder("\u00E1")));
            assertFalse(HttpCharSequences.containsIgnoreCase(source, 0, value.length(), null));
        } finally {
            source.release();
        }
    }

    private static int fold(int value) {
        return value >= 'A' && value <= 'Z' ? value + 32 : value;
    }

    private static boolean scalar(ByteBuf source, int offset, int length, String right) {
        for (int i = 0; i < length; i++) {
            if (fold(source.getByte(offset + i) & 0xFF) != fold(right.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
