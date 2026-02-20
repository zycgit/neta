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
package net.hasor.neta.codec.spdy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpProtocolException;

/**
 * SPDY header block codec.
 * <p>
 * In SPDY/3.1, headers are serialized using a simple name-value pairs format:
 * <pre>
 *   +------------------------------------+
 *   | Number of Name/Value pairs (int32) |
 *   +------------------------------------+
 *   |     Length of name (int32)          |
 *   +------------------------------------+
 *   |           Name (string)            |
 *   +------------------------------------+
 *   |     Length of value (int32)         |
 *   +------------------------------------+
 *   |           Value (string)           |
 *   +------------------------------------+
 *   |  ... (repeated for each pair) ...  |
 *   +------------------------------------+
 * </pre>
 * <p>
 * In the SPDY/3.1 specification, these header blocks are typically
 * compressed with zlib/deflate. This implementation supports both
 * raw and compressed modes for flexibility.
 */
public final class SpdyHeaderBlockCodec {

    private SpdyHeaderBlockCodec() {
    }

    /**
     * Encodes HTTP headers into SPDY header block format (uncompressed).
     * @param headers the HTTP headers to encode
     * @return the serialized header block bytes
     */
    public static byte[] encode(HttpHeaders headers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // Count pairs
        int count = 0;
        for (Map.Entry<String, String> ignored : headers) {
            count++;
        }

        // Write number of pairs
        writeInt32(out, count);

        // Write each pair
        for (Map.Entry<String, String> entry : headers) {
            String name = entry.getKey().toLowerCase();
            String value = entry.getValue();

            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);

            writeInt32(out, nameBytes.length);
            out.write(nameBytes, 0, nameBytes.length);
            writeInt32(out, valueBytes.length);
            out.write(valueBytes, 0, valueBytes.length);
        }

        return out.toByteArray();
    }

    /**
     * Decodes a SPDY header block into HTTP headers (uncompressed).
     * @param data the header block byte array
     * @param offset start offset
     * @param length number of bytes
     * @return the decoded HTTP headers
     */
    public static HttpHeaders decode(byte[] data, int offset, int length) {
        HttpHeaders headers = new HttpHeaders();
        int end = offset + length;
        int pos = offset;

        if (pos + 4 > end) {
            throw new HttpProtocolException("SPDY: header block too short for pair count");
        }

        int numPairs = readInt32(data, pos);
        pos += 4;

        for (int i = 0; i < numPairs; i++) {
            // Read name
            if (pos + 4 > end) {
                throw new HttpProtocolException("SPDY: truncated header block (name length)");
            }
            int nameLen = readInt32(data, pos);
            pos += 4;
            if (nameLen < 0 || pos + nameLen > end) {
                throw new HttpProtocolException("SPDY: invalid header name length: " + nameLen);
            }
            String name = new String(data, pos, nameLen, StandardCharsets.US_ASCII);
            pos += nameLen;

            // Read value
            if (pos + 4 > end) {
                throw new HttpProtocolException("SPDY: truncated header block (value length)");
            }
            int valueLen = readInt32(data, pos);
            pos += 4;
            if (valueLen < 0 || pos + valueLen > end) {
                throw new HttpProtocolException("SPDY: invalid header value length: " + valueLen);
            }
            String value = new String(data, pos, valueLen, StandardCharsets.US_ASCII);
            pos += valueLen;

            // SPDY uses NUL-separated multiple values
            if (value.indexOf('\0') >= 0) {
                for (String v : value.split("\0")) {
                    headers.add(name, v);
                }
            } else {
                headers.add(name, value);
            }
        }

        return headers;
    }

    /** Writes a 32-bit big-endian integer. */
    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** Reads a 32-bit big-endian integer. */
    private static int readInt32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
