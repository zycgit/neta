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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
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
    private static final String          CRLF  = "\r\n";
    private final        String          boundary;
    private final        Charset         charset;
    // Track whether any binary (byte[]) part has been started
    private final        List<PartEntry> parts = new ArrayList<>();

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
        parts.add(new PartEntry(name, null, null, ByteBuf.wrap(value.getBytes(charset))));
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
        parts.add(new PartEntry(fieldName, filename, contentType, ByteBuf.wrap(data)));
        return this;
    }

    /**
     * Adds a {@link FileUpload} part directly.
     * The content ByteBuf is referenced directly without copying.
     */
    public MultipartEncoder addPart(FileUpload part) {
        parts.add(new PartEntry(part.name(), part.filename(), part.contentType(), part.content()));
        return this;
    }

    /**
     * Encodes all added parts and returns the complete multipart body as a {@link ByteBuf}.
     * <p>Uses {@code getBuffer} (not {@code writeBuffer}) to read part data so that
     * the source readerIndex is not advanced — multiple calls produce the same result.
     * <p>Caller is responsible for freeing the returned ByteBuf.
     */
    public ByteBuf encodeToBuf() {
        // Pre-cache boundary bytes to avoid per-part getBytes() calls
        byte[] boundaryPrefixBytes = ("--" + boundary + CRLF).getBytes(StandardCharsets.US_ASCII);
        byte[] crlfBytes = CRLF.getBytes(StandardCharsets.US_ASCII);
        byte[] finalBoundaryBytes = ("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.US_ASCII);

        // Estimate total size to minimize reallocations
        int estimate = finalBoundaryBytes.length;
        for (PartEntry entry : parts) {
            estimate += boundaryPrefixBytes.length + 256 + entry.data.readableBytes() + crlfBytes.length * 2;
        }

        ByteBuf dst = ByteBufAllocator.DEFAULT.buffer(estimate);
        for (PartEntry entry : parts) {
            // --boundary\r\n
            dst.writeBytes(boundaryPrefixBytes);
            // Content-Disposition header
            StringBuilder disp = new StringBuilder("Content-Disposition: ");
            disp.append(HttpHeaderValues.FORM_DATA).append("; ");
            disp.append(HttpHeaderValues.NAME).append("=\"").append(entry.name).append('"');
            if (entry.filename != null) {
                disp.append("; ").append(HttpHeaderValues.FILENAME).append("=\"").append(entry.filename).append('"');
            }
            disp.append(CRLF);
            dst.writeString(disp.toString(), charset);
            // Content-Type header (only for file parts)
            if (entry.contentType != null) {
                dst.writeString("Content-Type: " + entry.contentType + CRLF, StandardCharsets.US_ASCII);
            }
            // Blank line
            dst.writeBytes(crlfBytes);
            // Body — getBuffer does not advance source readerIndex (safe for multiple encode() calls)
            int bodyLen = entry.data.readableBytes();
            entry.data.getBuffer(0, dst, bodyLen);
            // CRLF after body
            dst.writeBytes(crlfBytes);
        }
        // Final boundary
        dst.writeBytes(finalBoundaryBytes);
        dst.markWriter();
        return dst;
    }

    /**
     * Encodes all added parts and returns the complete multipart body as a byte array.
     * Multiple calls produce the same result (parts are not cleared).
     */
    public byte[] encode() {
        ByteBuf buf = encodeToBuf();
        try {
            return buf.asByteArray();
        } finally {
            buf.free();
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static final class PartEntry {
        final String  name;
        final String  filename;
        final String  contentType;
        final ByteBuf data;

        PartEntry(String name, String filename, String contentType, ByteBuf data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }
}
