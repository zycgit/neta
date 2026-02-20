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
package net.hasor.neta.codec.http2;

import java.nio.charset.StandardCharsets;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpProtocolException;

/**
 * HPACK decoder as defined in RFC 7541.
 * <p>
 * Decodes a compressed header block into a set of HTTP header fields.
 * Supports indexed header field, literal header field with/without indexing,
 * and dynamic table size updates.
 */
public class HpackDecoder {
    private final HpackDynamicTable dynamicTable;
    private final int               maxHeaderListSize;

    // Reusable decode result fields to avoid array allocations in hot paths
    private int    decodedPos;
    private int    decodedInt;
    private String decodedString;

    /**
     * Creates a new HPACK decoder.
     * @param maxHeaderTableSize initial maximum dynamic table size
     * @param maxHeaderListSize maximum allowed total header list size
     */
    public HpackDecoder(int maxHeaderTableSize, int maxHeaderListSize) {
        this.dynamicTable = new HpackDynamicTable(maxHeaderTableSize);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Decodes a compressed header block fragment into HTTP headers.
     * @param data the compressed header block bytes
     * @param offset start offset in the data array
     * @param length number of bytes to decode
     * @return the decoded HTTP headers
     * @throws HttpProtocolException if the header block is malformed
     */
    public HttpHeaders decode(byte[] data, int offset, int length) {
        HttpHeaders headers = new HttpHeaders();
        int end = offset + length;
        int pos = offset;
        int totalSize = 0;

        while (pos < end) {
            int b = data[pos] & 0xFF;

            if ((b & 0x80) != 0) {
                // 1xxxxxxx - Indexed Header Field (RFC 7541, Section 6.1)
                decodeInteger(data, pos, end, 7);
                pos = decodedPos;
                int index = decodedInt;
                if (index == 0) {
                    throw new HttpProtocolException("HPACK: invalid indexed header field index 0");
                }
                HpackHeaderField entry = getEntry(index);
                headers.add(entry.name(), entry.value());
                totalSize += entry.name().length() + entry.value().length();
            } else if ((b & 0xC0) == 0x40) {
                // 01xxxxxx - Literal Header Field with Incremental Indexing (RFC 7541, Section 6.2.1)
                decodeInteger(data, pos, end, 6);
                pos = decodedPos;
                int nameIndex = decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = decodedPos;
                    name = decodedString;
                }

                decodeString(data, pos, end);
                pos = decodedPos;
                String value = decodedString;

                headers.add(name, value);
                dynamicTable.add(new HpackHeaderField(name, value));
                totalSize += name.length() + value.length();
            } else if ((b & 0xF0) == 0x00) {
                // 0000xxxx - Literal Header Field without Indexing (RFC 7541, Section 6.2.2)
                decodeInteger(data, pos, end, 4);
                pos = decodedPos;
                int nameIndex = decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = decodedPos;
                    name = decodedString;
                }

                decodeString(data, pos, end);
                pos = decodedPos;
                String value = decodedString;

                headers.add(name, value);
                totalSize += name.length() + value.length();
            } else if ((b & 0xF0) == 0x10) {
                // 0001xxxx - Literal Header Field Never Indexed (RFC 7541, Section 6.2.3)
                decodeInteger(data, pos, end, 4);
                pos = decodedPos;
                int nameIndex = decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = decodedPos;
                    name = decodedString;
                }

                decodeString(data, pos, end);
                pos = decodedPos;
                String value = decodedString;

                headers.add(name, value);
                totalSize += name.length() + value.length();
            } else if ((b & 0xE0) == 0x20) {
                // 001xxxxx - Dynamic Table Size Update (RFC 7541, Section 6.3)
                decodeInteger(data, pos, end, 5);
                pos = decodedPos;
                int newMaxSize = decodedInt;
                dynamicTable.setMaxSize(newMaxSize);
            } else {
                throw new HttpProtocolException("HPACK: unknown header field representation: 0x" + Integer.toHexString(b));
            }

            if (totalSize > maxHeaderListSize) {
                throw new HttpProtocolException("HPACK: header list size exceeds maximum: " + totalSize + " > " + maxHeaderListSize);
            }
        }

        return headers;
    }

    /**
     * Gets an entry from the combined static + dynamic table.
     * @param index 1-based HPACK index
     * @return the header field entry
     */
    private HpackHeaderField getEntry(int index) {
        if (index <= HpackStaticTable.LENGTH) {
            return HpackStaticTable.get(index);
        }
        int dynamicIdx = index - HpackStaticTable.LENGTH - 1;
        return dynamicTable.get(dynamicIdx);
    }

    /**
     * Decodes an HPACK integer representation (RFC 7541, Section 5.1).
     * @param data the byte array
     * @param pos current position
     * @param end end position
     * @param prefixBits number of prefix bits (1-8)
     * @return int[2]: [0]=new position, [1]=decoded value
     */
    private void decodeInteger(byte[] data, int pos, int end, int prefixBits) {
        int prefixMask = (1 << prefixBits) - 1;
        int value = data[pos] & prefixMask;
        pos++;

        if (value < prefixMask) {
            this.decodedPos = pos;
            this.decodedInt = value;
            return;
        }

        // Multi-byte integer
        int shift = 0;
        int b;
        do {
            if (pos >= end) {
                throw new HttpProtocolException("HPACK: truncated integer");
            }
            b = data[pos] & 0xFF;
            pos++;
            value += (b & 0x7F) << shift;
            shift += 7;
            if (shift > 28) {
                throw new HttpProtocolException("HPACK: integer overflow");
            }
        } while ((b & 0x80) != 0);

        this.decodedPos = pos;
        this.decodedInt = value;
    }

    /**
     * Decodes an HPACK string literal (RFC 7541, Section 5.2).
     * Supports both raw and Huffman-encoded strings.
     * @param data the byte array
     * @param pos current position
     * @param end end position
     * @return Object[2]: [0]=new position (Integer), [1]=decoded string (String)
     */
    private void decodeString(byte[] data, int pos, int end) {
        if (pos >= end) {
            throw new HttpProtocolException("HPACK: truncated string");
        }

        boolean huffman = (data[pos] & 0x80) != 0;
        decodeInteger(data, pos, end, 7);
        pos = this.decodedPos;
        int strLen = this.decodedInt;

        if (pos + strLen > end) {
            throw new HttpProtocolException("HPACK: string length exceeds available data");
        }

        String value;
        if (huffman) {
            value = HpackHuffman.decode(data, pos, strLen);
        } else {
            value = new String(data, pos, strLen, StandardCharsets.ISO_8859_1);
        }
        pos += strLen;

        this.decodedPos = pos;
        this.decodedString = value;
    }

    /** Updates the dynamic table maximum size (called on SETTINGS change). */
    public void setMaxHeaderTableSize(int maxSize) {
        dynamicTable.setMaxSize(maxSize);
    }
}
