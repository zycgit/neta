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
package net.hasor.neta.codec.http.multipart;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;

/**
 * Decodes a {@code multipart/form-data} request body into a list of {@link FileUpload} parts.
 * <p>This decoder is a pure utility type. It works on a fully aggregated request-body {@link ByteBuf}
 * together with the {@code boundary} string extracted from the {@code Content-Type} header.
 * The current implementation returns only parts that can produce a valid {@code Content-Disposition}
 * header containing a {@code name} parameter.
 * <h3>Usage Example</h3>
 * <pre>
 *   String boundary = MultipartDecoder.extractBoundary(request.headers().get(HttpHeaderNames.CONTENT_TYPE));
 *   List&lt;FileUpload&gt; parts = MultipartDecoder.decode(request.content(), boundary);
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class MultipartDecoder {
    /**
     * Extracts the boundary from a {@code Content-Type} header value, for example:
     * {@code "multipart/form-data; boundary=----WebKitFormBoundary"}.
     * @param contentType the complete {@code Content-Type} header value
     * @return the boundary string, or {@code null} if it cannot be found
     */
    public static String extractBoundary(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return null;
        }

        for (String token : contentType.split(";")) {
            token = token.trim();
            String lower = token.toLowerCase();
            if (StringUtils.startsWith(lower, "boundary=")) {
                String boundary = token.substring("boundary=".length()).trim();
                // Remove wrapping quotes when present.
                if (boundary.length() >= 2 && boundary.charAt(0) == '"' && boundary.charAt(boundary.length() - 1) == '"') {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }

        return null;
    }

    /**
     * Decodes a {@code multipart/form-data} request body.
     * @param body the complete request-body bytes; returns an empty list when passed {@code null} or empty content
     * @param boundary the boundary string, which must not be null or empty
     * @return an unmodifiable part list
     * @throws IllegalArgumentException if the boundary is null or empty
     */
    public static List<FileUpload> decode(ByteBuf body, String boundary) {
        return decode(body, boundary, StandardCharsets.UTF_8);
    }

    /**
     * Parses headers with the specified charset and decodes the {@code multipart/form-data} request body.
     * The current implementation skips parts that have no empty-line separator, no {@code name}
     * parameter, or an incomplete structure.
     * @param body the complete request-body bytes
     * @param boundary the boundary string
     * @param charset the charset used to parse headers
     * @return an unmodifiable part list
     */
    public static List<FileUpload> decode(ByteBuf body, String boundary, Charset charset) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("boundary must not be null or empty");
        }
        if (body == null || body.readableBytes() == 0) {
            return Collections.emptyList();
        }

        byte[] delimiterBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int bodyLen = body.readableBytes();

        List<FileUpload> parts = new ArrayList<>(4);

        // Find and iterate over all boundary positions.
        int pos = 0;
        while (pos < bodyLen) {
            int delimPos = indexOfInBuf(body, delimiterBytes, pos);
            if (delimPos < 0) {
                break;
            }

            // Skip the delimiter line, including the delimiter itself and the optional \r\n or --.
            int afterDelim = delimPos + delimiterBytes.length;
            if (afterDelim + 2 <= bodyLen) {
                // Check whether this is the closing boundary "--".
                if (body.getByte(afterDelim) == '-' && body.getByte(afterDelim + 1) == '-') {
                    break; // End of the multipart body.
                }
                // Skip the CRLF following the delimiter.
                if (body.getByte(afterDelim) == '\r' && body.getByte(afterDelim + 1) == '\n') {
                    afterDelim += 2;
                } else if (body.getByte(afterDelim) == '\n') {
                    afterDelim += 1;
                }
            } else {
                break;
            }

            // Find the next boundary to determine the end position of the current part.
            int nextDelimPos = indexOfInBuf(body, delimiterBytes, afterDelim);
            if (nextDelimPos < 0) {
                break;
            }

            // The current part content ends at the CRLF right before the next boundary.
            int partEnd = nextDelimPos;
            if (partEnd >= 2 && body.getByte(partEnd - 2) == '\r' && body.getByte(partEnd - 1) == '\n') {
                partEnd -= 2;
            } else if (partEnd >= 1 && body.getByte(partEnd - 1) == '\n') {
                partEnd -= 1;
            }

            // Parse headers and body content between afterDelim and partEnd.
            FileUpload part = parsePart(body, afterDelim, partEnd, charset);
            if (part != null) {
                parts.add(part);
            }

            pos = nextDelimPos;
        }

        return Collections.unmodifiableList(parts);
    }

    // -------------------------------------------------------------------------
    // Internal helper methods
    // -------------------------------------------------------------------------

    private static FileUpload parsePart(ByteBuf data, int start, int end, Charset charset) {
        // Find the empty line separating headers from the body (\r\n\r\n or \n\n).
        int headerEnd = -1;
        int bodyStart = -1;
        for (int i = start; i < end - 1; i++) {
            if (data.getByte(i) == '\r' && i + 3 < end && data.getByte(i + 1) == '\n' && data.getByte(i + 2) == '\r' && data.getByte(i + 3) == '\n') {
                headerEnd = i;
                bodyStart = i + 4;
                break;
            } else if (data.getByte(i) == '\n' && i + 1 < end && data.getByte(i + 1) == '\n') {
                headerEnd = i;
                bodyStart = i + 2;
                break;
            }
        }
        if (headerEnd < 0 || bodyStart < 0) {
            return null;
        }

        // Parse headers directly from the ByteBuf to avoid copying them into a byte array.
        Map<String, String> headers = parseHeaders(data, start, headerEnd, charset);

        // Parse Content-Disposition.
        String disposition = headers.get(HttpHeaderNames.CONTENT_DISPOSITION);
        String fieldName = null;
        String filename = null;
        if (disposition != null) {
            fieldName = extractParam(disposition, HttpHeaderValues.NAME);
            filename = extractParam(disposition, HttpHeaderValues.FILENAME);
        }
        if (fieldName == null) {
            return null; // Missing name, so skip the malformed part.
        }

        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);

        // Extract body bytes and copy them into a new ByteBuf through getBuffer.
        int bodyLen = end - bodyStart;
        ByteBuf content;
        if (bodyLen > 0) {
            content = ByteBufAllocator.DEFAULT.buffer(bodyLen);
            data.getBuffer(bodyStart, content, bodyLen);
            content.markWriter();
        } else {
            content = ByteBuf.EMPTY;
        }

        return new DefaultFileUpload(fieldName, filename, contentType, content, headers);
    }

    private static Map<String, String> parseHeaders(ByteBuf data, int start, int end, Charset charset) {
        Map<String, String> headers = new LinkedHashMap<>(4, 1.0f);
        int pos = start;
        while (pos < end) {
            // Find the line ending (\r\n or \n).
            int lineEnd = -1;
            int nextStart = -1;
            for (int i = pos; i < end; i++) {
                if (data.getByte(i) == '\r' && i + 1 < end && data.getByte(i + 1) == '\n') {
                    lineEnd = i;
                    nextStart = i + 2;
                    break;
                } else if (data.getByte(i) == '\n') {
                    lineEnd = i;
                    nextStart = i + 1;
                    break;
                }
            }
            if (lineEnd < 0) {
                lineEnd = end;
                nextStart = end;
            }

            // Parse the header line inside the pos..lineEnd range.
            if (lineEnd > pos) {
                int colon = -1;
                for (int i = pos; i < lineEnd; i++) {
                    if (data.getByte(i) == ':') {
                        colon = i;
                        break;
                    }
                }
                if (colon > pos) {
                    // Trim whitespace around the key boundaries.
                    int keyEnd = colon;
                    while (keyEnd > pos && data.getByte(keyEnd - 1) <= ' ') {
                        keyEnd--;
                    }
                    int keyStart = pos;
                    while (keyStart < keyEnd && data.getByte(keyStart) <= ' ') {
                        keyStart++;
                    }
                    int keyLen = keyEnd - keyStart;

                    // Reuse constants for common header names to avoid extra String allocations.
                    String key;
                    if (keyLen == 19 && regionMatchesBuf(data, keyStart, HttpHeaderNames.CONTENT_DISPOSITION)) {
                        key = HttpHeaderNames.CONTENT_DISPOSITION;
                    } else if (keyLen == 12 && regionMatchesBuf(data, keyStart, HttpHeaderNames.CONTENT_TYPE)) {
                        key = HttpHeaderNames.CONTENT_TYPE;
                    } else {
                        key = data.getString(keyStart, keyLen, StandardCharsets.US_ASCII).toLowerCase();
                    }

                    // Trim the value and extract the string directly from the ByteBuf.
                    int valStart = colon + 1;
                    while (valStart < lineEnd && data.getByte(valStart) <= ' ') {
                        valStart++;
                    }
                    int valEnd = lineEnd;
                    while (valEnd > valStart && data.getByte(valEnd - 1) <= ' ') {
                        valEnd--;
                    }
                    String value = (valEnd > valStart) ? data.getString(valStart, valEnd - valStart, charset) : "";

                    headers.put(key, value);
                }
            }
            pos = nextStart;
        }
        return headers;
    }

    /** Extracts the specified parameter from a header value, for example {@code name="field"} -> {@code "field"}. */
    private static String extractParam(String header, String param) {
        String searchKey = param + "=";
        int idx = indexOfIgnoreCase(header, searchKey, 0);
        if (idx < 0) {
            return null;
        }
        int start = idx + searchKey.length();
        if (start >= header.length()) {
            return null;
        }
        if (header.charAt(start) == '"') {
            int end = header.indexOf('"', start + 1);
            if (end < 0) {
                end = header.length();
            }
            return header.substring(start + 1, end);
        } else {
            int end = start;
            while (end < header.length() && header.charAt(end) != ';' && header.charAt(end) != ' ') {
                end++;
            }
            return header.substring(start, end);
        }
    }

    /** Performs a case-insensitive indexOf without creating temporary lowercase strings. */
    private static int indexOfIgnoreCase(String str, String search, int fromIndex) {
        int searchLen = search.length();
        int maxIdx = str.length() - searchLen;
        for (int i = fromIndex; i <= maxIdx; i++) {
            if (str.regionMatches(true, i, search, 0, searchLen)) {
                return i;
            }
        }
        return -1;
    }

    /** Performs an optimized naive match on a ByteBuf byte sequence, first fast-skipping on the leading byte. */
    private static int indexOfInBuf(ByteBuf buf, byte[] needle, int fromIndex) {
        if (needle.length == 0) {
            return fromIndex;
        }

        byte first = needle[0];
        int bufLen = buf.readableBytes();
        int maxI = bufLen - needle.length;
        for (int i = fromIndex; i <= maxI; i++) {
            // Fast-skip until the first byte matches.
            if (buf.getByte(i) != first) {
                while (++i <= maxI && buf.getByte(i) != first) {
                }
            }
            if (i <= maxI) {
                // Verify the remaining bytes.
                boolean match = true;
                for (int j = 1; j < needle.length; j++) {
                    if (buf.getByte(i + j) != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return i;
                }
            }
        }

        return -1;
    }

    /** Performs a case-insensitive match between a ByteBuf region and a lowercase ASCII string constant. */
    private static boolean regionMatchesBuf(ByteBuf buf, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            byte b = buf.getByte(offset + i);
            char c = (b >= 'A' && b <= 'Z') ? (char) (b + 32) : (char) (b & 0xFF);
            if (c != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
