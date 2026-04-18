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
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link FileUpload}, storing part metadata and the content-buffer reference.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultFileUpload implements FileUpload {
    private final String              name;
    private final String              filename;
    private final String              contentType;
    private final ByteBuf             content;
    private final Map<String, String> headers;

    /**
     * Creates a regular form-field part without a filename or content type.
     */
    public DefaultFileUpload(String name, ByteBuf content) {
        this(name, null, null, content, new LinkedHashMap<>());
    }

    /**
     * Creates a file-upload part.
     * @param name the form field name
     * @param filename the original filename, which may be {@code null} for regular fields
     * @param contentType the MIME type of the part body, which may be {@code null}
     * @param content the raw content bytes
     * @param headers the additional part-header map; the current implementation stores this reference directly
     */
    public DefaultFileUpload(String name, String filename, String contentType, ByteBuf content, Map<String, String> headers) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("part name must not be null or empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.name = name;
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
        this.headers = headers;
    }

    /**
     * Returns the form field name.
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the uploaded filename.
     */
    @Override
    public String filename() {
        return filename;
    }

    /**
     * Returns the content type of the part.
     */
    @Override
    public String contentType() {
        return contentType;
    }

    /**
     * Returns the part body content.
     */
    @Override
    public ByteBuf content() {
        return content;
    }

    /**
     * Returns a part-header value by name.
     * The lookup parameter is converted to lowercase before accessing the internal map.
     */
    @Override
    public String header(String name) {
        if (name != null) {
            return headers.get(name.toLowerCase());
        } else {
            return null;
        }
    }

    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
        }
    }

    /**
     * Returns the string representation of the current upload part.
     */
    @Override
    public String toString() {
        return "DefaultFileUpload{name='" + name + '\'' + ", filename='" + filename + '\'' + ", contentType='" + contentType + '\'' + '}';
    }
}
