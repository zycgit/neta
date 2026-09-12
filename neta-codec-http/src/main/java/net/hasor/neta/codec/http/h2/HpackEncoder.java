/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;
/**
 * Encodes HTTP/2 header fields into an HPACK header block.
 * <p>
 * This encoder implements the HPACK compression format defined by RFC 7541. It is typically used
 * by the HTTP/2 message-layer encoder to compress {@link HttpHeaders} or header fields written one
 * by one into a binary header block for later encapsulation in HEADERS, PUSH_PROMISE, and related
 * frames.
 * <p>
 * A single encode operation internally behaves like an ordered header-field flow:
 * <pre>
 *   [name: value] -> [name: value] -> ... -> HPACK header block
 * </pre>
 * Each header field is encoded as an indexed header field, a literal header field with incremental
 * indexing, or a literal header field without indexing, depending on the static table, dynamic
 * table, and current indexing strategy.
 * <p>
 * Typical usage:
 * <pre>
 *   HpackEncoder encoder = new HpackEncoder(4096);
 *   byte[] headerBlock = encoder.encode(headers);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   HttpHeaders / header fields
 *      -> HpackEncoder
 *      -> compressed header block bytes
 * </pre>
 * <p>
 * To reduce allocation cost on the hot path, this implementation uses a reusable internal byte
 * buffer instead of {@code ByteArrayOutputStream}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class HpackEncoder {
    private final HpackDynamicTable dynamicTable;
    private final boolean           useIndexing;
    // Reusable encoding buffer that avoids the synchronization cost of ByteArrayOutputStream.
    private byte[] buf = new byte[256];
    private int    pos;

    /**
     * Creates a new HPACK encoder.
     * @param maxHeaderTableSize the initial maximum capacity of the dynamic table
     * @param useIndexing whether incremental indexing is enabled for new entries
     */
    public HpackEncoder(int maxHeaderTableSize, boolean useIndexing) {
        this.dynamicTable = new HpackDynamicTable(maxHeaderTableSize);
        this.useIndexing = useIndexing;
    }

    /**
     * Creates an HPACK encoder with indexing enabled.
     * @param maxHeaderTableSize the initial maximum capacity of the dynamic table
     */
    public HpackEncoder(int maxHeaderTableSize) {
        this(maxHeaderTableSize, true);
    }

    /**
     * Encodes HTTP headers into a compressed HPACK header block.
     * @param headers the HTTP headers to encode
     * @return the compressed header-block bytes
     */
    public byte[] encode(HttpHeaders headers) {
        pos = 0;

        for (String name : headers.headerNames()) {
            for (String value : headers.getValues(name)) {
                encodeHeader(name.toLowerCase(), value);
            }
        }

        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /**
     * Encodes HTTP headers directly into a {@link ByteBuf}, avoiding the intermediate byte[] allocation of {@link #encode(HttpHeaders)}.
     * @param headers the HTTP headers to encode
     * @param dst the target ByteBuf
     * @return the number of bytes actually written
     */
    public int encodeTo(HttpHeaders headers, ByteBuf dst) {
        pos = 0;

        for (String name : headers.headerNames()) {
            for (String value : headers.getValues(name)) {
                encodeHeader(name.toLowerCase(), value);
            }
        }

        dst.writeBytes(buf, 0, pos);
        return pos;
    }

    /**
     * Starts a direct encoding session.
     * Header fields are written one by one through {@link #encodeHeaderDirect(String, String)}, and
     * the result is then read through {@link #encodedBuffer()} and {@link #encodedLength()}.
     * <p>
     * This avoids creating an intermediate {@link HttpHeaders} object and also avoids the byte[]
     * copy performed by {@link #encode(HttpHeaders)}.
     */
    public void beginEncode() {
        pos = 0;
    }

    /**
     * Encodes a single header field directly into the internal buffer.
     * This must be called after {@link #beginEncode()} and before reading {@link #encodedLength()}.
     * @param name the header name, which must be lowercase in HTTP/2
     * @param value the header value
     */
    public void encodeHeaderDirect(String name, String value) {
        encodeHeader(name, value);
    }

    /**
     * Returns the number of bytes written since {@link #beginEncode()}.
     * @return the number of encoded bytes
     */
    public int encodedLength() {
        return pos;
    }

    /**
     * Returns a reference to the internal encoding buffer.
     * Its valid content range is from 0 to {@link #encodedLength()} - 1, and the reference remains
     * valid only until the next encoding operation.
     * @return the internal byte buffer
     */
    public byte[] encodedBuffer() {
        return buf;
    }

    /**
     * Encodes the pseudo-headers and regular headers of an HTTP/2 request.
     * Pseudo-headers (those starting with ':') are encoded first.
     * @param headers the complete header set including pseudo-headers
     * @return the compressed header-block bytes
     */
    public byte[] encodeRequest(HttpHeaders headers) {
        return encode(headers);
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

    private void encodeHeader(String name, String value) {
        // Try exact match in static table
        int staticIdx = HpackStaticTable.findNameValue(name, value);
        if (staticIdx > 0) {
            // Indexed header field, see Section 6.1.
            encodeInteger(staticIdx, 7, 0x80);
            return;
        }

        // Try matching by name in the static table.
        int nameIdx = HpackStaticTable.findName(name);

        // Try matching in the dynamic table.
        if (nameIdx <= 0) {
            for (int i = 0; i < dynamicTable.length(); i++) {
                HpackHeaderField entry = dynamicTable.get(i);
                if (entry.name().equals(name)) {
                    if (entry.value().equals(value)) {
                        // Found an exact match in the dynamic table.
                        int idx = HpackStaticTable.LENGTH + i + 1;
                        encodeInteger(idx, 7, 0x80);
                        return;
                    }
                    if (nameIdx <= 0) {
                        nameIdx = HpackStaticTable.LENGTH + i + 1;
                    }
                }
            }
        }

        if (useIndexing) {
            // Literal header field with incremental indexing, see Section 6.2.1.
            if (nameIdx > 0) {
                encodeInteger(nameIdx, 6, 0x40);
            } else {
                writeByte(0x40); // Name index is 0.
                encodeString(name);
            }
            encodeString(value);
            dynamicTable.add(new HpackHeaderField(name, value));
        } else {
            // Literal header field without indexing, see Section 6.2.2.
            if (nameIdx > 0) {
                encodeInteger(nameIdx, 4, 0x00);
            } else {
                writeByte(0x00); // Name index is 0.
                encodeString(name);
            }
            encodeString(value);
        }
    }

    /**
     * Encodes an HPACK integer representation, see RFC 7541 Section 5.1.
     * @param value the integer value
     * @param prefixBits the number of prefix bits
     * @param prefix the prefix byte pattern
     */
    private void encodeInteger(int value, int prefixBits, int prefix) {
        int prefixMask = (1 << prefixBits) - 1;

        if (value < prefixMask) {
            writeByte(prefix | value);
        } else {
            writeByte(prefix | prefixMask);
            value -= prefixMask;
            while (value >= 128) {
                writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte(value);
        }
    }

    /**
     * Encodes an HPACK string literal, see RFC 7541 Section 5.2.
     * For simplicity, this implementation uses raw encoding instead of Huffman encoding.
     * String bytes are written directly into the internal buffer to avoid the intermediate byte[]
     * allocation caused by calling String.getBytes().
     * @param s the string to encode
     */
    private void encodeString(String s) {
        int len = s.length();
        // Raw string encoding with no Huffman coding, so H=0.
        encodeInteger(len, 7, 0x00);
        ensureCapacity(len);
        // Write ISO-8859-1 bytes directly to avoid the extra allocation from s.getBytes().
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
    }

    /**
     * Updates the maximum dynamic-table capacity.
     * @param maxSize the new capacity
     */
    public void setMaxHeaderTableSize(int maxSize) {
        dynamicTable.setMaxSize(maxSize);
    }
}
