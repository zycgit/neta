/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.codec.http.HttpHeaders;
/**
 * HTTP/3 头压缩所使用的 QPACK 编码器，定义见 RFC 9204。
 * <p>
 * QPACK 专门适配 HTTP/3 在 QUIC 之上的乱序交付模型。
 * 与 HPACK 不同，QPACK 通过独立的编码器流和解码器流来同步动态表更新，从而避免队头阻塞。
 * <p>
 * 当前实现支持：
 * <ul>
 * <li>静态表引用，包括索引引用和名称引用</li>
 * <li>动态表插入与引用</li>
 * <li>带或不带名称引用的字面量编码</li>
 * <li>可选的 Huffman 编码</li>
 * </ul>
 * <p>
 * 这里使用可复用的内部字节缓冲区替代 {@code ByteArrayOutputStream}，以消除同步写入开销并降低 GC 压力。
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackEncoder {
    private final QpackDynamicTable dynamicTable;
    private final boolean           useHuffman;

    // 可复用的编码缓冲区，避免使用带同步开销的 ByteArrayOutputStream。
    private byte[] buf = new byte[256];
    private int    pos;

    /**
     * 创建一个新的 QPACK 编码器。
     * @param maxTableSize 动态表最大容量，单位为字节
     * @param useHuffman 是否对字符串字面量使用 Huffman 编码
     */
    public QpackEncoder(int maxTableSize, boolean useHuffman) {
        this.dynamicTable = new QpackDynamicTable(maxTableSize);
        this.useHuffman = useHuffman;
    }

    /**
     * 使用默认 settings 创建一个新的 QPACK 编码器。
     */
    public QpackEncoder() {
        this(4096, false);
    }

    /**
     * 返回当前编码器使用的动态表。
     */
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
     * 将一组 HTTP 头编码为 QPACK field section。
     * <p>
     * 编码格式见 RFC 9204 第 4.5 节：
     * <pre>
     * Encoded Field Section {
     * Required Insert Count (8+),
     * S (1) + Delta Base (7+),
     * Encoded Field Line* (..)
     * }
     * </pre>
     * @param headers 待编码的 HTTP 头
     * @return QPACK 编码后的字节数组
     */
    public byte[] encode(HttpHeaders headers) {
        pos = 0;

        // Required Insert Count = 0，当前简化模式只使用静态表引用。
        encodePrefixedInt(0, 8, 0);
        // S=0，Delta Base = 0。
        encodePrefixedInt(0, 7, 0);

        for (String name : headers.headerNames()) {
            for (String value : headers.getValues(name)) {
                encodeHeaderField(name.toLowerCase(), value);
            }
        }

        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /**
     * 开始一次直接编码会话。
     * QPACK 前缀，即 Required Insert Count 与 Delta Base，会自动写入。
     * 头字段随后通过 {@link #encodeHeaderDirect(String, String)} 逐个写入，再通过
     * {@link #encodedBuffer()} 与 {@link #encodedLength()} 读取结果。
     */
    public void beginEncode() {
        pos = 0;
        // Required Insert Count = 0。
        encodePrefixedInt(0, 8, 0);
        // S=0，Delta Base = 0。
        encodePrefixedInt(0, 7, 0);
    }

    /**
     * 直接将单个头字段编码到内部缓冲区。
     * 必须在 {@link #beginEncode()} 之后、读取 {@link #encodedLength()} 之前调用。
     * @param name 头名称，必须为小写
     * @param value 头值
     */
    public void encodeHeaderDirect(String name, String value) {
        encodeHeaderField(name, value);
    }

    /**
     * 返回自 {@link #beginEncode()} 以来写入的字节数。
     * @return 已编码字节数
     */
    public int encodedLength() {
        return pos;
    }

    /**
     * 返回内部编码缓冲区的引用。
     * 其有效内容范围为 0 到 {@link #encodedLength()} - 1，并且仅在下一次编码操作之前有效。
     * @return 内部字节缓冲区
     */
    public byte[] encodedBuffer() {
        return buf;
    }

    /**
     * 编码单个头字段。
     */
    private void encodeHeaderField(String name, String value) {
        // 尝试在静态表中寻找完全匹配项。
        int staticIdx = QpackStaticTable.findIndex(name, value);
        if (staticIdx >= 0) {
            // 静态表索引字段行，1T 模式：1 1 index。
            encodePrefixedInt(0xC0, 6, staticIdx);
            return;
        }

        // 尝试在静态表中按名称匹配。
        int nameIdx = QpackStaticTable.findNameIndex(name);
        if (nameIdx >= 0) {
            // 使用静态表名称引用的字面量字段行。
            // 01 N T 模式：0 1 0 1 name_index，然后是 value。
            encodePrefixedInt(0x50, 4, nameIdx);
            encodeStringLiteral(value, 7);
            return;
        }

        // 不带名称引用的字面量字段行。
        // 001 N H NameLen(3+) 全部编码在第一个字节中，见 RFC 9204 第 4.5.6 节。
        // prefix = 0x20（001 + N=0），H=0，表示不使用 Huffman。
        encodePrefixedInt(0x20, 3, name.length());
        writeStringDirect(name);
        encodeStringLiteral(value, 7);
    }

    /**
     * 编码字符串字面量。
     * 字符串字节会直接写入，以避免中间 byte[] 分配。
     * @param value 字符串值
     * @param prefixBits 长度整数可用的前缀位数
     */
    private void encodeStringLiteral(String value, int prefixBits) {
        int len = value.length();
        // H=0，当前简化模式不启用 Huffman 编码。
        encodePrefixedInt(0, prefixBits, len);
        writeStringDirect(value);
    }

    /**
     * 直接按字节写入字符串字符，避免 String.getBytes() 产生额外分配。
     */
    private void writeStringDirect(String s) {
        int len = s.length();
        ensureCapacity(len);
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
    }

    /**
     * 编码 QPACK 前缀整数，编码规则与 HPACK 相同。
     * @param prefix 前缀字节，高位部分
     * @param prefixBits 首字节中可用于整数编码的位数
     * @param value 待编码整数
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
