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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Decodes a {@code multipart/form-data} body into a list of {@link FileUpload} parts.
 * <p>The decoder is a pure utility (no pipeline integration needed) that operates on a
 * fully-aggregated body {@link ByteBuf} together with the {@code boundary} string extracted
 * from the {@code Content-Type} header.
 * <h3>Usage</h3>
 * <pre>
 *   String boundary = MultipartDecoder.extractBoundary(request.headers().get("content-type"));
 *   List&lt;FileUpload&gt; parts = MultipartDecoder.decode(request.content(), boundary);
 * </pre>
 */
public final class MultipartDecoder {

    private MultipartDecoder() {
    }

    /**
     * Extracts the boundary value from a {@code Content-Type} header value such as
     * {@code "multipart/form-data; boundary=----WebKitFormBoundary"}.
     * @param contentType the full {@code Content-Type} header value
     * @return the boundary string, or {@code null} if not found
     */
    public static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String token : contentType.split(";")) {
            token = token.trim();
            String lower = token.toLowerCase();
            if (lower.startsWith("boundary=")) {
                String boundary = token.substring("boundary=".length()).trim();
                // Strip surrounding quotes if present
                if (boundary.length() >= 2 && boundary.charAt(0) == '"' && boundary.charAt(boundary.length() - 1) == '"') {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    /**
     * Decodes a {@code multipart/form-data} body.
     * @param body the complete body bytes (not null)
     * @param boundary the boundary string (not null, not empty)
     * @return an unmodifiable list of decoded parts
     * @throws IllegalArgumentException if boundary is null or empty
     */
    public static List<FileUpload> decode(ByteBuf body, String boundary) {
        return decode(body, boundary, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a {@code multipart/form-data} body using the specified charset for header parsing.
     * @param body the complete body bytes
     * @param boundary the boundary string
     * @param charset charset used for header parsing
     * @return an unmodifiable list of decoded parts
     */
    public static List<FileUpload> decode(ByteBuf body, String boundary, Charset charset) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("boundary must not be null or empty");
        }
        if (body == null || body.readableBytes() == 0) {
            return Collections.emptyList();
        }

        byte[] bodyBytes = readAllBytes(body);
        byte[] delimiterBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] finalDelimiterBytes = ("--" + boundary + "--").getBytes(StandardCharsets.US_ASCII);

        List<FileUpload> parts = new ArrayList<>();

        // Find and iterate over all boundary positions
        int pos = 0;
        while (pos < bodyBytes.length) {
            int delimPos = indexOf(bodyBytes, delimiterBytes, pos);
            if (delimPos < 0) {
                break;
            }

            // Skip past the delimiter line (delimiter + optional \r\n or --)
            int afterDelim = delimPos + delimiterBytes.length;
            if (afterDelim + 2 <= bodyBytes.length) {
                // Check for final boundary "--"
                if (bodyBytes[afterDelim] == '-' && bodyBytes[afterDelim + 1] == '-') {
                    break; // end of multipart
                }
                // Skip CRLF after delimiter
                if (bodyBytes[afterDelim] == '\r' && bodyBytes[afterDelim + 1] == '\n') {
                    afterDelim += 2;
                } else if (bodyBytes[afterDelim] == '\n') {
                    afterDelim += 1;
                }
            } else {
                break;
            }

            // Find the next boundary to determine the end of this part's body
            int nextDelimPos = indexOf(bodyBytes, delimiterBytes, afterDelim);
            if (nextDelimPos < 0) {
                break;
            }

            // The part content ends at the CRLF just before the next boundary
            int partEnd = nextDelimPos;
            if (partEnd >= 2 && bodyBytes[partEnd - 2] == '\r' && bodyBytes[partEnd - 1] == '\n') {
                partEnd -= 2;
            } else if (partEnd >= 1 && bodyBytes[partEnd - 1] == '\n') {
                partEnd -= 1;
            }

            // Parse headers and body from afterDelim..partEnd
            FileUpload part = parsePart(bodyBytes, afterDelim, partEnd, charset);
            if (part != null) {
                parts.add(part);
            }

            pos = nextDelimPos;
        }

        return Collections.unmodifiableList(parts);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static FileUpload parsePart(byte[] data, int start, int end, Charset charset) {
        // Find the blank line separating headers from body (\r\n\r\n or \n\n)
        int headerEnd = -1;
        int bodyStart = -1;
        for (int i = start; i < end - 1; i++) {
            if (data[i] == '\r' && i + 3 < end && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                headerEnd = i;
                bodyStart = i + 4;
                break;
            } else if (data[i] == '\n' && i + 1 < end && data[i + 1] == '\n') {
                headerEnd = i;
                bodyStart = i + 2;
                break;
            }
        }
        if (headerEnd < 0 || bodyStart < 0) {
            return null;
        }

        // Parse headers
        String headerSection = new String(data, start, headerEnd - start, charset);
        Map<String, String> headers = parseHeaders(headerSection);

        // Parse Content-Disposition
        String disposition = headers.get("content-disposition");
        String fieldName = null;
        String filename = null;
        if (disposition != null) {
            fieldName = extractParam(disposition, "name");
            filename = extractParam(disposition, "filename");
        }
        if (fieldName == null) {
            return null; // no name → skip malformed part
        }

        String contentType = headers.get("content-type");

        // Extract body bytes
        int bodyLen = end - bodyStart;
        ByteBuf content = ByteBufAllocator.DEFAULT.buffer(Math.max(bodyLen, 1), Integer.MAX_VALUE);
        if (bodyLen > 0) {
            content.writeBytes(data, bodyStart, bodyLen);
            content.markWriter();
        }

        return new DefaultFileUpload(fieldName, filename, contentType, content, headers);
    }

    private static Map<String, String> parseHeaders(String headerSection) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = headerSection.split("\r\n|\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }
        return headers;
    }

    /** Extracts a named parameter from a header value, e.g. {@code name="field"} → {@code "field"}. */
    private static String extractParam(String header, String param) {
        String searchKey = param + "=";
        int idx = header.toLowerCase().indexOf(searchKey.toLowerCase());
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

    private static byte[] readAllBytes(ByteBuf buf) {
        int len = buf.readableBytes();
        byte[] result = new byte[len];
        buf.readBytes(result, 0, len);
        return result;
    }

    /** Boyer-Moore-Horspool-style simple indexOf for byte arrays. */
    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        if (needle.length == 0) {
            return fromIndex;
        }
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
