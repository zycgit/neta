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
package net.hasor.neta.http.internal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.http.ServletResponse;

/**
 * Mock implementation of {@link ServletResponse} for unit testing.
 */
public class MockServletResponse implements ServletResponse {
    private final Map<String, List<String>> headers    = new LinkedHashMap<>();
    private final ByteArrayOutputStream     body       = new ByteArrayOutputStream();
    private       int                       statusCode = 200;
    private       boolean                   committed  = false;
    private       String                    contentType;

    @Override
    public int getStatus() {
        return this.statusCode;
    }

    @Override
    public void setStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public void setHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        this.headers.put(name.toLowerCase(), list);
    }

    @Override
    public void addHeader(String name, String value) {
        this.headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
    }

    @Override
    public String getHeader(String name) {
        List<String> values = this.headers.get(name.toLowerCase());
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = this.headers.get(name.toLowerCase());
        return values != null ? values : Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return this.headers.keySet();
    }

    @Override
    public boolean containsHeader(String name) {
        return this.headers.containsKey(name.toLowerCase());
    }

    @Override
    public String getContentType() {
        return this.contentType;
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
        setHeader("content-type", type);
    }

    @Override
    public void setContentLength(long length) {
        setHeader("content-length", String.valueOf(length));
    }

    @Override
    public void addCookie(Cookie cookie) {
        addHeader("set-cookie", cookie.name() + "=" + cookie.value());
    }

    @Override
    public OutputStream getOutputStream() {
        return this.body;
    }

    @Override
    public void write(String content) throws IOException {
        if (content != null) {
            this.body.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void write(byte[] content) throws IOException {
        if (content != null) {
            this.body.write(content);
        }
    }

    @Override
    public void write(byte[] content, int offset, int length) throws IOException {
        if (content != null) {
            this.body.write(content, offset, length);
        }
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        this.statusCode = 302;
        setHeader("location", location);
        this.committed = true;
    }

    @Override
    public void sendError(int statusCode) throws IOException {
        this.statusCode = statusCode;
        this.committed = true;
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException {
        this.statusCode = statusCode;
        this.committed = true;
    }

    @Override
    public boolean isCommitted() {
        return this.committed;
    }

    /** Returns the body content as string */
    public String getBodyAsString() {
        return this.body.toString();
    }

    /** Returns the body content as bytes */
    public byte[] getBodyBytes() {
        return this.body.toByteArray();
    }
}
