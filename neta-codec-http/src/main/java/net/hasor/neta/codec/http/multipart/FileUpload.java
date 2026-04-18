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
import net.hasor.cobble.function.Release;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Represents a single part (field) inside a {@code multipart/form-data} request body, as defined by
 * <a href="https://tools.ietf.org/html/rfc7578">RFC 7578</a>.
 * <p>Each part contains a set of MIME headers, most importantly {@code Content-Disposition}, plus
 * an entity payload. For file-upload parts, {@link #filename()} is not null.
 * <h3>Raw Part Example</h3>
 * <pre>
 * --boundary\r\n
 * Content-Disposition: form-data; name="file"; filename="photo.png"\r\n
 * Content-Type: image/png\r\n
 * \r\n
 * &lt;binary data&gt;
 * --boundary--\r\n
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface FileUpload extends Release {
    /**
     * Returns the {@code name} parameter from the {@code Content-Disposition} header,
     * which is the HTML form field name.
     */
    String name();

    /**
     * Returns the {@code filename} parameter from the {@code Content-Disposition} header.
     * Regular form fields return {@code null}.
     */
    String filename();

    /**
     * Returns the {@code Content-Type} of the current part, or {@code null} if it is not specified.
     */
    String contentType();

    /**
     * Returns the raw body content of the current part.
     */
    ByteBuf content();

    /**
     * Returns the value of an additional part header by name, ignoring case, or {@code null} if it is absent.
     */
    String header(String name);

    /**
     * Releases the current part content when the request lifecycle ends.
     */
    @Override
    void release();
}
