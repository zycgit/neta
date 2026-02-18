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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default mutable implementation of {@link FileUpload}.
 */
public class DefaultFileUpload implements FileUpload {

    private final String              name;
    private final String              filename;
    private final String              contentType;
    private final ByteBuf             content;
    private final Map<String, String> headers;

    /**
     * Creates a plain form field (no filename, no content-type).
     */
    public DefaultFileUpload(String name, ByteBuf content) {
        this(name, null, null, content, new LinkedHashMap<>());
    }

    /**
     * Creates a file upload part.
     * @param name form field name
     * @param filename original filename (may be {@code null} for plain fields)
     * @param contentType MIME type of the part body (may be {@code null})
     * @param content raw body bytes
     * @param headers extra part headers (may be empty, not null)
     */
    public DefaultFileUpload(String name, String filename, String contentType, ByteBuf content, Map<String, String> headers) {
        if (name == null || name.isEmpty()) {
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

    @Override
    public String name() {
        return name;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public String contentType() {
        return contentType;
    }

    @Override
    public ByteBuf content() {
        return content;
    }

    @Override
    public String header(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name.toLowerCase());
    }

    @Override
    public String toString() {
        return "DefaultFileUpload{name='" + name + '\'' + ", filename='" + filename + '\'' + ", contentType='" + contentType + '\'' + '}';
    }
}
