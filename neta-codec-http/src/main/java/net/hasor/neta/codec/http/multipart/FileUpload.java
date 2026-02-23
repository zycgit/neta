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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Represents a single part (field) within a {@code multipart/form-data} request body,
 * as specified in <a href="https://tools.ietf.org/html/rfc7578">RFC 7578</a>.
 * <p>Each part has a set of MIME headers (most importantly {@code Content-Disposition})
 * and a body payload.  For file uploads the {@link #filename()} will be non-null.
 * <h3>Example raw part</h3>
 * <pre>
 * --boundary\r\n
 * Content-Disposition: form-data; name="file"; filename="photo.png"\r\n
 * Content-Type: image/png\r\n
 * \r\n
 * &lt;binary data&gt;
 * --boundary--\r\n
 * </pre>
 */
public interface FileUpload {
    /**
     * Returns the {@code name} parameter of the {@code Content-Disposition} header,
     * i.e. the HTML form field name.
     */
    String name();

    /**
     * Returns the {@code filename} parameter of the {@code Content-Disposition} header,
     * or {@code null} if this part is a plain form field (not a file).
     */
    String filename();

    /**
     * Returns the {@code Content-Type} of this part, or {@code null} if not specified.
     */
    String contentType();

    /**
     * Returns the raw body of this part.
     */
    ByteBuf content();

    /**
     * Returns the value of any additional part header by name (case-insensitive),
     * or {@code null} if not present.
     */
    String header(String name);
}
