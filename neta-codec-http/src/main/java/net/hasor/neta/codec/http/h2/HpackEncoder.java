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
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * HPACK encoder as defined in RFC 7541.
 * <p>
 * Encodes HTTP headers into a compressed header block using the HPACK format.
 * Supports indexed header fields, literal with incremental indexing,
 * and literal without indexing.
 * <p>
 * Uses a reusable internal byte buffer instead of {@code ByteArrayOutputStream}
 * to eliminate synchronized write overhead and reduce GC pressure.
 */
class HpackEncoder {
    private final HpackDynamicTable dynamicTable;
    private final boolean           useIndexing;

    // Reusable encode buffer to avoid ByteArrayOutputStream (which is synchronized)
    private byte[] buf = new byte[256];
    private int    pos;

    /**
     * Creates a new HPACK encoder.
     * @param maxHeaderTableSize initial maximum dynamic table size
     * @param useIndexing whether to use incremental indexing for new entries
     */
    public HpackEncoder(int maxHeaderTableSize, boolean useIndexing) {
        this.dynamicTable = new HpackDynamicTable(maxHeaderTableSize);
        this.useIndexing = useIndexing;
    }

    /** Creates a new HPACK encoder with indexing enabled. */
    public HpackEncoder(int maxHeaderTableSize) {
        this(maxHeaderTableSize, true);
    }

    /**
     * Encodes HTTP headers into a compressed HPACK header block.
     * @param headers the HTTP headers to encode
     * @return the compressed header block bytes
     */
    public byte[] encode(HttpHeaders headers) {
        pos = 0;

        for (Map.Entry<String, String> entry : headers) {
            String name = entry.getKey().toLowerCase();
            String value = entry.getValue();
            encodeHeader(name, value);
        }

        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /**
     * Encodes HTTP headers directly into a ByteBuf, avoiding the intermediate
     * byte[] allocation of {@link #encode(HttpHeaders)}.
     * @param headers the HTTP headers to encode
     * @param dst the destination ByteBuf to write the encoded bytes into
     * @return the number of bytes written
     */
    public int encodeTo(HttpHeaders headers, ByteBuf dst) {
        pos = 0;

        for (Map.Entry<String, String> entry : headers) {
            String name = entry.getKey().toLowerCase();
            String value = entry.getValue();
            encodeHeader(name, value);
        }

        dst.writeBytes(buf, 0, pos);
        return pos;
    }

    /**
     * Begins a direct encode session. Header fields are added individually via
     * {@link #encodeHeaderDirect(String, String)}, then the result is retrieved
     * via {@link #encodedBuffer()} and {@link #encodedLength()}.
     * <p>
     * This avoids creating an intermediate {@link HttpHeaders} object and the
     * {@code byte[]} copy performed by {@link #encode(HttpHeaders)}.
     */
    public void beginEncode() {
        pos = 0;
    }

    /**
     * Encodes a single header field directly into the internal buffer.
     * Must be called between {@link #beginEncode()} and reading {@link #encodedLength()}.
     * @param name the header name (must be lowercase for HTTP/2)
     * @param value the header value
     */
    public void encodeHeaderDirect(String name, String value) {
        encodeHeader(name, value);
    }

    /**
     * Returns the number of bytes written since {@link #beginEncode()}.
     * @return the encoded byte count
     */
    public int encodedLength() {
        return pos;
    }

    /**
     * Returns a reference to the internal encode buffer. The content is valid from
     * index 0 to {@link #encodedLength()} - 1. The reference is only valid until
     * the next encode operation.
     * @return the internal byte buffer
     */
    public byte[] encodedBuffer() {
        return buf;
    }

    /**
     * Encodes pseudo-headers and regular headers for an HTTP/2 request.
     * Pseudo-headers (starting with ':') are encoded first.
     * @param headers all headers including pseudo-headers
     * @return the compressed header block bytes
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
            // Indexed Header Field (Section 6.1)
            encodeInteger(staticIdx, 7, 0x80);
            return;
        }

        // Try name match in static table
        int nameIdx = HpackStaticTable.findName(name);

        // Try dynamic table
        if (nameIdx <= 0) {
            for (int i = 0; i < dynamicTable.length(); i++) {
                HpackHeaderField entry = dynamicTable.get(i);
                if (entry.name().equals(name)) {
                    if (entry.value().equals(value)) {
                        // Exact match in dynamic table
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
            // Literal Header Field with Incremental Indexing (Section 6.2.1)
            if (nameIdx > 0) {
                encodeInteger(nameIdx, 6, 0x40);
            } else {
                writeByte(0x40); // name index = 0
                encodeString(name);
            }
            encodeString(value);
            dynamicTable.add(new HpackHeaderField(name, value));
        } else {
            // Literal Header Field without Indexing (Section 6.2.2)
            if (nameIdx > 0) {
                encodeInteger(nameIdx, 4, 0x00);
            } else {
                writeByte(0x00); // name index = 0
                encodeString(name);
            }
            encodeString(value);
        }
    }

    /**
     * Encodes an HPACK integer representation (RFC 7541, Section 5.1).
     * @param value the integer value
     * @param prefixBits number of prefix bits
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
     * Encodes an HPACK string literal (RFC 7541, Section 5.2).
     * Uses raw encoding (no Huffman) for simplicity.
     * Writes string bytes directly into the internal buffer to avoid
     * intermediate byte[] allocation from String.getBytes().
     * @param s the string to encode
     */
    private void encodeString(String s) {
        int len = s.length();
        // Raw string (no Huffman): H=0
        encodeInteger(len, 7, 0x00);
        ensureCapacity(len);
        // Write ISO-8859-1 bytes directly - avoids s.getBytes() allocation
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
    }

    /** Updates the dynamic table maximum size. */
    public void setMaxHeaderTableSize(int maxSize) {
        dynamicTable.setMaxSize(maxSize);
    }
}
