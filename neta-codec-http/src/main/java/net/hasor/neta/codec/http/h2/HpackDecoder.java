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
package net.hasor.neta.codec.http.h2;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderTooLargeException;

/**
 * Decodes an HPACK header block into an HTTP/2 header collection.
 * <p>
 * This decoder implements the HPACK decompression flow defined by RFC 7541. It is typically used
 * by the HTTP/2 message-layer decoder to restore the compressed header blocks carried by HEADERS,
 * PUSH_PROMISE, and CONTINUATION into {@link DefaultHttpHeaders} so that later stages can rebuild
 * request headers, response headers, or trailers.
 * <p>
 * A single decode operation internally behaves like an ordered header-block consumption flow:
 * <pre>
 *   compressed header block bytes
 *      -> HpackDecoder
 *      -> [name: value] + [name: value] + ...
 * </pre>
 * Input fragments are interpreted as indexed header fields, literal header fields with or without
 * indexing, never-indexed literal header fields, and dynamic-table size update instructions.
 * <p>
 * Typical usage:
 * <pre>
 *   HpackDecoder decoder = new HpackDecoder(4096, 16384);
 *   DefaultHttpHeaders headers = decoder.decode(data, offset, length);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   compressed header block bytes
 *      -> HpackDecoder
 *      -> DefaultHttpHeaders
 * </pre>
 * <p>
 * The decoder also maintains the dynamic-table state and enforces the configured upper bound on the
 * total header-list size.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class HpackDecoder {
    private final HpackDynamicTable dynamicTable;
    private final int               maxHeaderListSize;
    // Reusable decoded-result fields to avoid array allocations on the hot path.
    private int    decodedPos;
    private int    decodedInt;
    private String decodedString;

    /**
     * Creates a new HPACK decoder.
     * @param maxHeaderTableSize the initial maximum capacity of the dynamic table
     * @param maxHeaderListSize the maximum total size allowed for the header list
     */
    public HpackDecoder(int maxHeaderTableSize, int maxHeaderListSize) {
        this.dynamicTable = new HpackDynamicTable(maxHeaderTableSize);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Decodes a compressed header-block fragment into HTTP headers.
     * @param data the compressed data
     * @param offset the starting offset
     * @param length the data length
     * @return the decoded HTTP header collection
     */
    public DefaultHttpHeaders decode(byte[] data, int offset, int length) {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        int end = offset + length;
        int pos = offset;
        int totalSize = 0;

        while (pos < end) {
            int b = data[pos] & 0xFF;

            if ((b & 0x80) != 0) {
                // 1xxxxxxx: indexed header field, see RFC 7541 Section 6.1.
                decodeInteger(data, pos, end, 7);
                pos = this.decodedPos;
                int index = this.decodedInt;
                if (index == 0) {
                    throw new HpackDecodingException("HPACK: invalid indexed header field index 0");
                }
                HpackHeaderField entry = getEntry(index);
                headers.addHeader(entry.name(), entry.value());
                totalSize += entry.name().length() + entry.value().length();
            } else if ((b & 0xC0) == 0x40) {
                // 01xxxxxx: literal header field with incremental indexing, see RFC 7541 Section 6.2.1.
                decodeInteger(data, pos, end, 6);
                pos = this.decodedPos;
                int nameIndex = this.decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = this.decodedPos;
                    name = this.decodedString;
                }

                decodeString(data, pos, end);
                pos = this.decodedPos;
                String value = this.decodedString;

                headers.addHeader(name, value);
                this.dynamicTable.add(new HpackHeaderField(name, value));
                totalSize += name.length() + value.length();
            } else if ((b & 0xF0) == 0x00) {
                // 0000xxxx: literal header field without indexing, see RFC 7541 Section 6.2.2.
                decodeInteger(data, pos, end, 4);
                pos = this.decodedPos;
                int nameIndex = this.decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = this.decodedPos;
                    name = this.decodedString;
                }

                decodeString(data, pos, end);
                pos = this.decodedPos;
                String value = this.decodedString;

                headers.addHeader(name, value);
                totalSize += name.length() + value.length();
            } else if ((b & 0xF0) == 0x10) {
                // 0001xxxx: never-indexed literal header field, see RFC 7541 Section 6.2.3.
                decodeInteger(data, pos, end, 4);
                pos = this.decodedPos;
                int nameIndex = this.decodedInt;

                String name;
                if (nameIndex > 0) {
                    name = getEntry(nameIndex).name();
                } else {
                    decodeString(data, pos, end);
                    pos = this.decodedPos;
                    name = this.decodedString;
                }

                decodeString(data, pos, end);
                pos = this.decodedPos;
                String value = this.decodedString;

                headers.addHeader(name, value);
                totalSize += name.length() + value.length();
            } else if ((b & 0xE0) == 0x20) {
                // 001xxxxx: dynamic table size update, see RFC 7541 Section 6.3.
                decodeInteger(data, pos, end, 5);
                pos = decodedPos;
                int newMaxSize = decodedInt;
                dynamicTable.setMaxSize(newMaxSize);
            } else {
                throw new HpackDecodingException("HPACK: unknown header field representation: 0x" + Integer.toHexString(b));
            }

            if (totalSize > this.maxHeaderListSize) {
                throw new HttpHeaderTooLargeException("HPACK: header list size exceeds maximum: " + totalSize + " > " + this.maxHeaderListSize, this.maxHeaderListSize, totalSize);
            }
        }

        return headers;
    }

    /**
     * Reads an entry from the combined view of the static table and dynamic table.
     */
    private HpackHeaderField getEntry(int index) {
        if (index <= HpackStaticTable.LENGTH) {
            return HpackStaticTable.get(index);
        }
        int dynamicIdx = index - HpackStaticTable.LENGTH - 1;
        return dynamicTable.get(dynamicIdx);
    }

    /**
     * Decodes an HPACK integer representation, see RFC 7541 Section 5.1.
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

        // Multi-byte integer encoding.
        int shift = 0;
        int b;
        do {
            if (pos >= end) {
                throw new HpackDecodingException("HPACK: truncated integer");
            }
            b = data[pos] & 0xFF;
            pos++;
            value += (b & 0x7F) << shift;
            shift += 7;
            if (shift > 28) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
        } while ((b & 0x80) != 0);

        this.decodedPos = pos;
        this.decodedInt = value;
    }

    /**
     * Decodes an HPACK string literal, see RFC 7541 Section 5.2.
     */
    private void decodeString(byte[] data, int pos, int end) {
        if (pos >= end) {
            throw new HpackDecodingException("HPACK: truncated string");
        }

        boolean huffman = (data[pos] & 0x80) != 0;
        decodeInteger(data, pos, end, 7);
        pos = this.decodedPos;
        int strLen = this.decodedInt;

        if (pos + strLen > end) {
            throw new HpackDecodingException("HPACK: string length exceeds available data");
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

    /**
     * Updates the maximum dynamic-table capacity, typically after a SETTINGS change.
     * @param maxSize the new capacity
     */
    public void setMaxHeaderTableSize(int maxSize) {
        this.dynamicTable.setMaxSize(maxSize);
    }
}
