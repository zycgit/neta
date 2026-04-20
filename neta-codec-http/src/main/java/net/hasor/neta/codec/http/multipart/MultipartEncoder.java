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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.HttpHeaderValues;
/**
 * Encodes a set of {@link FileUpload} parts into a {@code multipart/form-data} request body.
 * <p>The encoder produces both a directly usable request-body byte array and the matching
 * {@code Content-Type} header value that contains the boundary.
 * <h3>Usage Example</h3>
 * <pre>
 *   MultipartEncoder encoder = new MultipartEncoder();
 *   encoder.addField("username", "alice");
 *   encoder.addFile("avatar", "photo.png", "image/png", pngBytes);
 *   byte[] body    = encoder.encode();
 *   String ctValue = encoder.contentType(); // "multipart/form-data; boundary=..."
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class MultipartEncoder {
    private static final String CRLF = "\r\n";
    private final String        boundary;
    private final Charset       charset;
    // Stores all part entries waiting to be encoded.
    private final List<PartEntry> parts = new ArrayList<>();

    /** Creates an encoder with a random UUID boundary and the UTF-8 charset. */
    public MultipartEncoder() {
        this(UUID.randomUUID().toString().replace("-", ""), StandardCharsets.UTF_8);
    }

    /**
     * Creates an encoder with the specified boundary and charset.
     * @param boundary the multipart boundary; the current implementation writes delimiter lines with this raw value
     * @param charset the charset used to encode field names, filenames, and text values
     */
    public MultipartEncoder(String boundary, Charset charset) {
        if (StringUtils.isBlank(boundary)) {
            throw new IllegalArgumentException("boundary must not be null or empty");
        }
        this.boundary = boundary;
        this.charset = charset;
    }

    /** Returns the boundary used by the current encoder. */
    public String boundary() {
        return boundary;
    }

    /**
     * Returns the {@code Content-Type} header value corresponding to the current multipart request body,
     * for example {@code "multipart/form-data; boundary=abc123"}.
     */
    public String contentType() {
        return HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=" + boundary;
    }

    /**
     * Adds a plain-text form field.
     * @param name the field name
     * @param value the field value, encoded with the current encoder charset
     */
    public MultipartEncoder addField(String name, String value) {
        parts.add(PartEntry.forBytes(name, null, null, value.getBytes(charset)));
        return this;
    }

    /**
     * Adds a file-upload part.
     * @param fieldName the HTML form field name
     * @param filename the original filename
     * @param contentType the file MIME type
     * @param data the raw file bytes
     */
    public MultipartEncoder addFile(String fieldName, String filename, String contentType, byte[] data) {
        parts.add(PartEntry.forBytes(fieldName, filename, contentType, data));
        return this;
    }

    /**
     * Adds a {@link FileUpload} part directly.
     * The content ByteBuf is referenced directly and is not copied.
     */
    public MultipartEncoder addPart(FileUpload part) {
        parts.add(PartEntry.forBuffer(part.name(), part.filename(), part.contentType(), part.content()));
        return this;
    }

    /**
     * Encodes all added parts and returns the complete multipart request body as a {@link ByteBuf}.
     * <p>This method reads part data through {@code getBuffer}, so the source buffer readerIndex is left unchanged,
     * and repeated calls produce the same result.
     * <p>The caller is responsible for releasing the returned ByteBuf.
     */
    public ByteBuf encodeToBuf() {
        // Pre-cache boundary bytes to avoid repeated getBytes() calls for every part.
        byte[] boundaryPrefixBytes = ("--" + boundary + CRLF).getBytes(StandardCharsets.US_ASCII);
        byte[] crlfBytes = CRLF.getBytes(StandardCharsets.US_ASCII);
        byte[] finalBoundaryBytes = ("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.US_ASCII);

        // Estimate the total size to reduce reallocations as much as possible.
        int estimate = finalBoundaryBytes.length;
        for (PartEntry entry : parts) {
            estimate += boundaryPrefixBytes.length + 256 + entry.readableBytes() + crlfBytes.length * 2;
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
            // Content-Type header (needed only for file parts)
            if (entry.contentType != null) {
                dst.writeString("Content-Type: " + entry.contentType + CRLF, StandardCharsets.US_ASCII);
            }
            // Empty line
            dst.writeBytes(crlfBytes);
            // Body content is copied without mutating the original part payload.
            entry.writeTo(dst);
            // CRLF after the body content
            dst.writeBytes(crlfBytes);
        }

        // Closing boundary
        dst.writeBytes(finalBoundaryBytes);
        dst.markWriter();
        return dst;
    }

    /**
     * Encodes all added parts and returns the complete multipart request body as a byte array.
     * Repeated calls produce the same result, and the internal part list is not cleared.
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
    // Internal structure
    // -------------------------------------------------------------------------

    private static final class PartEntry {
        final String  name;
        final String  filename;
        final String  contentType;
        final byte[]  bytes;
        final ByteBuf data;

        static PartEntry forBytes(String name, String filename, String contentType, byte[] bytes) {
            return new PartEntry(name, filename, contentType, bytes, null);
        }

        static PartEntry forBuffer(String name, String filename, String contentType, ByteBuf data) {
            return new PartEntry(name, filename, contentType, null, data);
        }

        PartEntry(String name, String filename, String contentType, byte[] bytes, ByteBuf data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes;
            this.data = data;
        }

        int readableBytes() {
            return this.bytes != null ? this.bytes.length : this.data.readableBytes();
        }

        void writeTo(ByteBuf dst) {
            if (this.bytes != null) {
                dst.writeBytes(this.bytes);
                return;
            }

            int bodyLen = this.data.readableBytes();
            this.data.getBuffer(0, dst, bodyLen);
        }
    }
}
