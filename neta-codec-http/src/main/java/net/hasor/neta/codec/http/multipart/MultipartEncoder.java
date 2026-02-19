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
import java.util.UUID;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;

/**
 * Encodes a collection of {@link FileUpload} parts into a {@code multipart/form-data} body.
 * <p>The encoder produces a ready-to-use body byte array and the corresponding
 * {@code Content-Type} header value (which includes the boundary).
 * <h3>Usage</h3>
 * <pre>
 *   MultipartEncoder encoder = new MultipartEncoder();
 *   encoder.addField("username", "alice");
 *   encoder.addFile("avatar", "photo.png", "image/png", pngBytes);
 *   byte[] body    = encoder.encode();
 *   String ctValue = encoder.contentType(); // "multipart/form-data; boundary=..."
 * </pre>
 */
public class MultipartEncoder {

    private static final String CRLF = "\r\n";

    private final String                    boundary;
    private final Charset                   charset;
    private final StringBuilder             body;
    // Track whether any binary (byte[]) part has been started
    private final java.util.List<PartEntry> parts = new java.util.ArrayList<>();

    /** Creates an encoder with a random UUID boundary and UTF-8 charset. */
    public MultipartEncoder() {
        this(UUID.randomUUID().toString().replace("-", ""), StandardCharsets.UTF_8);
    }

    /**
     * Creates an encoder with the specified boundary and charset.
     * @param boundary the multipart boundary (must not contain "--")
     * @param charset charset used for encoding field names, filenames and text values
     */
    public MultipartEncoder(String boundary, Charset charset) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("boundary must not be null or empty");
        }
        this.boundary = boundary;
        this.charset = charset;
        this.body = new StringBuilder();
    }

    /** Returns the boundary used by this encoder. */
    public String boundary() {
        return boundary;
    }

    /**
     * Returns the value of the {@code Content-Type} header for this multipart body,
     * e.g. {@code "multipart/form-data; boundary=abc123"}.
     */
    public String contentType() {
        return HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=" + boundary;
    }

    /**
     * Adds a plain text form field.
     * @param name field name
     * @param value field value (encoded with this encoder's charset)
     */
    public MultipartEncoder addField(String name, String value) {
        parts.add(new PartEntry(name, null, null, value.getBytes(charset)));
        return this;
    }

    /**
     * Adds a file upload part.
     * @param fieldName HTML form field name
     * @param filename original filename
     * @param contentType MIME type of the file
     * @param data raw file bytes
     */
    public MultipartEncoder addFile(String fieldName, String filename, String contentType, byte[] data) {
        parts.add(new PartEntry(fieldName, filename, contentType, data));
        return this;
    }

    /**
     * Adds a {@link FileUpload} part directly.
     */
    public MultipartEncoder addPart(FileUpload part) {
        byte[] data;
        net.hasor.neta.bytebuf.ByteBuf content = part.content();
        int len = content.readableBytes();
        data = new byte[len];
        // read without advancing the readerIndex so the part can be reused
        for (int i = 0; i < len; i++) {
            data[i] = content.getByte(content.readerIndex() + i);
        }
        parts.add(new PartEntry(part.name(), part.filename(), part.contentType(), data));
        return this;
    }

    /**
     * Encodes all added parts and returns the complete multipart body as a byte array.
     * Multiple calls produce the same result (parts are not cleared).
     */
    public byte[] encode() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (PartEntry entry : parts) {
                // --boundary\r\n
                out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.US_ASCII));
                // Content-Disposition header
                StringBuilder disp = new StringBuilder("Content-Disposition: " + HttpHeaderValues.FORM_DATA + "; " + HttpHeaderValues.NAME + "=\"").append(entry.name).append('"');
                if (entry.filename != null) {
                    disp.append("; " + HttpHeaderValues.FILENAME + "=\"").append(entry.filename).append('"');
                }
                out.write((disp + CRLF).getBytes(charset));
                // Content-Type header (only for file parts)
                if (entry.contentType != null) {
                    out.write(("Content-Type: " + entry.contentType + CRLF).getBytes(StandardCharsets.US_ASCII));
                }
                // Blank line
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
                // Body
                out.write(entry.data);
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
            }
            // Final boundary
            out.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.US_ASCII));
        } catch (java.io.IOException e) {
            throw new RuntimeException("encoding failed", e);
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static final class PartEntry {
        final String name;
        final String filename;
        final String contentType;
        final byte[] data;

        PartEntry(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }
}
