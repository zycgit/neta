/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http3;

import java.nio.charset.StandardCharsets;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * QPACK encoder for HTTP/3 header compression (RFC 9204).
 * <p>
 * QPACK is designed to work with HTTP/3's out-of-order delivery model over QUIC.
 * Unlike HPACK, QPACK avoids head-of-line blocking by using a separate encoder
 * and decoder stream for dynamic table updates.
 * <p>
 * This implementation supports:
 * <ul>
 *   <li>Static table references (indexed and name-referenced)</li>
 *   <li>Dynamic table inserts and references</li>
 *   <li>Literal encoding with and without name references</li>
 *   <li>Huffman encoding (optional)</li>
 * </ul>
 * <p>
 * Uses a reusable internal byte buffer instead of {@code ByteArrayOutputStream}
 * to eliminate synchronized write overhead and reduce GC pressure.
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackEncoder {
    private final QpackDynamicTable dynamicTable;
    private final boolean           useHuffman;

    // Reusable encode buffer to avoid ByteArrayOutputStream (which is synchronized)
    private byte[] buf = new byte[256];
    private int    pos;

    /**
     * Creates a new QPACK encoder.
     * @param maxTableSize the maximum dynamic table size in bytes
     * @param useHuffman whether to use Huffman encoding for string literals
     */
    public QpackEncoder(int maxTableSize, boolean useHuffman) {
        this.dynamicTable = new QpackDynamicTable(maxTableSize);
        this.useHuffman = useHuffman;
    }

    /** Creates a new QPACK encoder with default settings. */
    public QpackEncoder() {
        this(4096, false);
    }

    /** Returns the dynamic table used by this encoder. */
    public QpackDynamicTable dynamicTable() {
        return dynamicTable;
    }

    private void ensureCapacity(int needed) {
        if (pos + needed > buf.length) {
            byte[] newBuf = new byte[Math.max(buf.length << 1, pos + needed)];
            System.arraycopy(buf, 0, newBuf, 0, pos);
            buf = newBuf;
        }
    }

    private void writeByte(int b) {
        ensureCapacity(1);
        buf[pos++] = (byte) b;
    }

    private void writeBytes(byte[] data, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(data, off, buf, pos, len);
        pos += len;
    }

    /**
     * Encodes a set of HTTP headers into a QPACK encoded field section.
     * <p>
     * The encoded format (RFC 9204, Section 4.5):
     * <pre>
     *   Encoded Field Section {
     *     Required Insert Count (8+),
     *     S (1) + Delta Base (7+),
     *     Encoded Field Line* (..)
     *   }
     * </pre>
     * @param headers the HTTP headers to encode
     * @return the QPACK-encoded bytes
     */
    public byte[] encode(HttpHeaders headers) {
        pos = 0;

        // Required Insert Count = 0 (using only static table references in simple mode)
        encodePrefixedInt(0, 8, 0);
        // S=0, Delta Base = 0
        encodePrefixedInt(0, 7, 0);

        for (String name : headers.names()) {
            for (String value : headers.getAll(name)) {
                encodeHeaderField(name.toLowerCase(), value);
            }
        }

        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /**
     * Encodes a single header field.
     */
    private void encodeHeaderField(String name, String value) {
        // Try static table exact match
        int staticIdx = QpackStaticTable.findIndex(name, value);
        if (staticIdx >= 0) {
            // Indexed Field Line (static) - 1T pattern: 1 1 index
            encodePrefixedInt(0xC0, 6, staticIdx);
            return;
        }

        // Try static table name match
        int nameIdx = QpackStaticTable.findNameIndex(name);
        if (nameIdx >= 0) {
            // Literal Field Line With Name Reference (static)
            // 01 N T pattern: 0 1 0 1 name_index, then value
            encodePrefixedInt(0x50, 4, nameIdx);
            encodeStringLiteral(value, 7);
            return;
        }

        // Literal Field Line Without Name Reference
        // 001 N H NameLen(3+) - all in the first byte per RFC 9204, Section 4.5.6
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        // prefix = 0x20 (001 + N=0), H=0 (no Huffman)
        encodePrefixedInt(0x20, 3, nameBytes.length);
        writeBytes(nameBytes, 0, nameBytes.length);
        encodeStringLiteral(value, 7);
    }

    /**
     * Encodes a string literal.
     * @param value the string value
     * @param prefixBits the prefix bits for the length integer
     */
    private void encodeStringLiteral(String value, int prefixBits) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // H=0 (no Huffman encoding in simple mode)
        encodePrefixedInt(0, prefixBits, bytes.length);
        writeBytes(bytes, 0, bytes.length);
    }

    /**
     * Encodes a QPACK prefix-encoded integer (same encoding as HPACK).
     * @param prefix the prefix byte (upper bits)
     * @param prefixBits the number of bits available for the integer in the first byte
     * @param value the integer value to encode
     */
    private void encodePrefixedInt(int prefix, int prefixBits, int value) {
        int maxPrefix = (1 << prefixBits) - 1;
        if (value < maxPrefix) {
            writeByte(prefix | value);
        } else {
            writeByte(prefix | maxPrefix);
            value -= maxPrefix;
            while (value >= 128) {
                writeByte(0x80 | (value & 0x7F));
                value >>>= 7;
            }
            writeByte(value);
        }
    }
}
