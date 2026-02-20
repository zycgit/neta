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
import net.hasor.neta.codec.http.HttpProtocolException;

/**
 * QPACK decoder for HTTP/3 header compression (RFC 9204).
 * <p>
 * Decodes QPACK-encoded field sections into {@link HttpHeaders}.
 * This decoder handles:
 * <ul>
 *   <li>Indexed field lines (static and dynamic table references)</li>
 *   <li>Literal field lines with name references</li>
 *   <li>Literal field lines without name references</li>
 * </ul>
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackDecoder {
    private final QpackDynamicTable dynamicTable;
    private final int               maxHeaderListSize;

    // Reusable decode result fields to avoid array allocations in hot paths
    private int    decodedPos;
    private int    decodedInt;
    private String decodedString;

    /**
     * Creates a new QPACK decoder.
     * @param maxTableSize the maximum dynamic table size in bytes
     * @param maxHeaderListSize the maximum allowed header list size
     */
    public QpackDecoder(int maxTableSize, int maxHeaderListSize) {
        this.dynamicTable = new QpackDynamicTable(maxTableSize);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /** Creates a new QPACK decoder with default settings. */
    public QpackDecoder() {
        this(4096, 65536);
    }

    /** Returns the dynamic table used by this decoder. */
    public QpackDynamicTable dynamicTable() {
        return dynamicTable;
    }

    /**
     * Decodes a QPACK-encoded field section into HTTP headers.
     * @param data the encoded bytes
     * @param offset the starting offset
     * @param length the number of bytes to decode
     * @return the decoded headers
     */
    public HttpHeaders decode(byte[] data, int offset, int length) {
        HttpHeaders headers = new HttpHeaders();
        int end = offset + length;
        int pos = offset;

        // Decode Required Insert Count
        decodePrefixedInt(data, pos, 8);
        int requiredInsertCount = decodedInt;
        pos = decodedPos;

        // Decode Sign bit + Delta Base
        boolean sign = (data[pos] & 0x80) != 0;
        decodePrefixedInt(data, pos, 7);
        int deltaBase = decodedInt;
        pos = decodedPos;

        int base;
        if (requiredInsertCount == 0) {
            base = 0;
        } else if (sign) {
            base = requiredInsertCount - deltaBase - 1;
        } else {
            base = requiredInsertCount + deltaBase;
        }

        int totalSize = 0;

        // Decode field lines
        while (pos < end) {
            int firstByte = data[pos] & 0xFF;

            if ((firstByte & 0x80) != 0) {
                // Indexed Field Line
                boolean isStatic = (firstByte & 0x40) != 0;
                decodePrefixedInt(data, pos, 6);
                int index = decodedInt;
                pos = decodedPos;

                QpackHeaderField field;
                if (isStatic) {
                    if (index >= QpackStaticTable.length()) {
                        throw new HttpProtocolException("QPACK: static table index out of range: " + index);
                    }
                    field = QpackStaticTable.get(index);
                } else {
                    // Dynamic table reference (absolute = base - index - 1 for post-base)
                    int absIndex = base - index - 1;
                    field = dynamicTable.get(absIndex);
                }

                headers.add(field.name(), field.value());
                totalSize += field.size();
            } else if ((firstByte & 0x40) != 0) {
                // Literal Field Line With Name Reference
                boolean isStatic = (firstByte & 0x10) != 0;
                decodePrefixedInt(data, pos, 4);
                int nameIndex = decodedInt;
                pos = decodedPos;

                String name;
                if (isStatic) {
                    if (nameIndex >= QpackStaticTable.length()) {
                        throw new HttpProtocolException("QPACK: static table name index out of range: " + nameIndex);
                    }
                    name = QpackStaticTable.get(nameIndex).name();
                } else {
                    int absIndex = base - nameIndex - 1;
                    name = dynamicTable.get(absIndex).name();
                }

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.add(name, value);
                totalSize += name.length() + value.length() + 32;
            } else if ((firstByte & 0x20) != 0) {
                // Literal Field Line Without Name Reference
                // 001 N H NameLen(3+) - name length starts in the first byte
                decodePrefixedInt(data, pos, 3);
                int nameLength = decodedInt;
                pos = decodedPos;

                // Read name directly from source array, avoiding intermediate byte[] copy
                String name = new String(data, pos, nameLength, StandardCharsets.UTF_8);
                pos += nameLength;

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.add(name, value);
                totalSize += name.length() + value.length() + 32;
            } else if ((firstByte & 0x10) != 0) {
                // Indexed Field Line With Post-Base Index
                decodePrefixedInt(data, pos, 4);
                int index = decodedInt;
                pos = decodedPos;

                int absIndex = base + index;
                QpackHeaderField field = dynamicTable.get(absIndex);
                headers.add(field.name(), field.value());
                totalSize += field.size();
            } else {
                // Literal Field Line With Post-Base Name Reference
                decodePrefixedInt(data, pos, 3);
                int nameIndex = decodedInt;
                pos = decodedPos;

                int absIndex = base + nameIndex;
                String name = dynamicTable.get(absIndex).name();

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.add(name, value);
                totalSize += name.length() + value.length() + 32;
            }

            if (totalSize > maxHeaderListSize) {
                throw new HttpProtocolException("QPACK: header list size exceeds limit: " + totalSize);
            }
        }

        return headers;
    }

    /**
     * Decodes a QPACK prefix-encoded integer.
     * @param data the encoded bytes
     * @param offset the offset
     * @param prefixBits the number of prefix bits
     * @return a two-element array: [value, bytesConsumed]
     */
    private void decodePrefixedInt(byte[] data, int offset, int prefixBits) {
        int maxPrefix = (1 << prefixBits) - 1;
        int value = data[offset] & maxPrefix;
        int bytesConsumed = 1;

        if (value == maxPrefix) {
            int m = 0;
            int b;
            do {
                b = data[offset + bytesConsumed] & 0xFF;
                value += (b & 0x7F) << m;
                m += 7;
                bytesConsumed++;
            } while ((b & 0x80) != 0);
        }

        this.decodedInt = value;
        this.decodedPos = offset + bytesConsumed;
    }

    /**
     * Decodes a string literal at the given offset.
     * Reads directly from the source byte[] to avoid intermediate byte[] allocation.
     * @param data the encoded bytes
     * @param offset the offset
     */
    private void decodeStringLiteral(byte[] data, int offset) {
        boolean huffman = (data[offset] & 0x80) != 0;
        decodePrefixedInt(data, offset, 7);
        int strLen = this.decodedInt;
        int dataStart = this.decodedPos;

        // Read directly from source array, avoiding intermediate byte[] copy
        this.decodedString = new String(data, dataStart, strLen, StandardCharsets.UTF_8);
        this.decodedPos = dataStart + strLen;
    }
}
