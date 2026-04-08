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
package net.hasor.neta.codec.http.h3;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderTooLargeException;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * HTTP/3 头压缩所使用的 QPACK 解码器，定义见 RFC 9204。
 * <p>
 * 它负责把 QPACK 编码后的字段区段解码为 {@link HttpHeaders}。
 * 当前解码器支持：
 * <ul>
 *   <li>索引字段行，包括静态表、动态表和 Post-Base 引用</li>
 *   <li>带名称引用的字面量字段行，包括静态表、动态表和 Post-Base 名称引用</li>
 *   <li>不带名称引用的字面量字段行</li>
 * </ul>
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackDecoder {
    private final QpackDynamicTable dynamicTable;
    private final int               maxHeaderListSize;

    // 可复用的解码结果字段，避免热点路径上的数组分配。
    private int    decodedPos;
    private int    decodedInt;
    private String decodedString;

    /**
     * 创建一个新的 QPACK 解码器。
     * @param maxTableSize 动态表最大容量，单位为字节
     * @param maxHeaderListSize header list 允许的最大大小
     */
    public QpackDecoder(int maxTableSize, int maxHeaderListSize) {
        this.dynamicTable = new QpackDynamicTable(maxTableSize);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * 使用默认 settings 创建一个新的 QPACK 解码器。
     */
    public QpackDecoder() {
        this(4096, 65536);
    }

    /**
     * 返回当前解码器使用的动态表。
     */
    public QpackDynamicTable dynamicTable() {
        return dynamicTable;
    }

    /**
     * 将 QPACK 编码后的字段区段解码为 HTTP 头。
     * @param data 编码后的字节数组
     * @param offset 起始偏移
     * @param length 需要解码的字节数
     * @return 解码后的 HTTP 头
     */
    public HttpHeaders decode(byte[] data, int offset, int length) {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        int end = offset + length;
        int pos = offset;

        // 解码 Required Insert Count。
        decodePrefixedInt(data, pos, 8);
        int requiredInsertCount = decodedInt;
        pos = decodedPos;

        // 解码 Sign 位与 Delta Base。
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

        // 解码字段行。
        while (pos < end) {
            int firstByte = data[pos] & 0xFF;

            if ((firstByte & 0x80) != 0) {
                // 索引字段行。
                boolean isStatic = (firstByte & 0x40) != 0;
                decodePrefixedInt(data, pos, 6);
                int index = decodedInt;
                pos = decodedPos;

                QpackHeaderField field;
                if (isStatic) {
                    if (index >= QpackStaticTable.length()) {
                        throw new QpackDecodingException("QPACK: static table index out of range: " + index);
                    }
                    field = QpackStaticTable.get(index);
                } else {
                    // 动态表引用，绝对索引按 base - index - 1 计算。
                    int absIndex = base - index - 1;
                    field = dynamicTable.get(absIndex);
                }

                headers.addHeader(field.name(), field.value());
                totalSize += field.size();
            } else if ((firstByte & 0x40) != 0) {
                // 带名称引用的字面量字段行。
                boolean isStatic = (firstByte & 0x10) != 0;
                decodePrefixedInt(data, pos, 4);
                int nameIndex = decodedInt;
                pos = decodedPos;

                String name;
                if (isStatic) {
                    if (nameIndex >= QpackStaticTable.length()) {
                        throw new QpackDecodingException("QPACK: static table name index out of range: " + nameIndex);
                    }
                    name = QpackStaticTable.get(nameIndex).name();
                } else {
                    int absIndex = base - nameIndex - 1;
                    name = dynamicTable.get(absIndex).name();
                }

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.addHeader(name, value);
                totalSize += name.length() + value.length() + 32;
            } else if ((firstByte & 0x20) != 0) {
                // 不带名称引用的字面量字段行。
                // 001 N H NameLen(3+)，名称长度从首字节开始编码。
                decodePrefixedInt(data, pos, 3);
                int nameLength = decodedInt;
                pos = decodedPos;

                // 直接从源数组读取名称，避免中间 byte[] 拷贝。
                String name = new String(data, pos, nameLength, StandardCharsets.UTF_8);
                pos += nameLength;

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.addHeader(name, value);
                totalSize += name.length() + value.length() + 32;
            } else if ((firstByte & 0x10) != 0) {
                // 带 Post-Base 索引的索引字段行。
                decodePrefixedInt(data, pos, 4);
                int index = decodedInt;
                pos = decodedPos;

                int absIndex = base + index;
                QpackHeaderField field = dynamicTable.get(absIndex);
                headers.addHeader(field.name(), field.value());
                totalSize += field.size();
            } else {
                // 带 Post-Base 名称引用的字面量字段行。
                decodePrefixedInt(data, pos, 3);
                int nameIndex = decodedInt;
                pos = decodedPos;

                int absIndex = base + nameIndex;
                String name = dynamicTable.get(absIndex).name();

                decodeStringLiteral(data, pos);
                String value = decodedString;
                pos = decodedPos;

                headers.addHeader(name, value);
                totalSize += name.length() + value.length() + 32;
            }

            if (totalSize > maxHeaderListSize) {
                throw new HttpHeaderTooLargeException("QPACK: header list size exceeds limit: " + totalSize + " > " + maxHeaderListSize, maxHeaderListSize, totalSize);
            }
        }

        return headers;
    }

    /**
     * 解码 QPACK 前缀整数。
     * @param data 编码字节数组
     * @param offset 起始偏移
     * @param prefixBits 前缀位数
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
     * 从指定偏移位置解码字符串字面量。
     * 为避免中间 byte[] 分配，这里会直接从源数组读取。
     * @param data 编码字节数组
     * @param offset 起始偏移
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
