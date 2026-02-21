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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.http.HttpSession;
import net.hasor.neta.http.ServletRequest;

/**
 * Mock implementation of {@link ServletRequest} for unit testing.
 */
public class MockServletRequest implements ServletRequest {
    private final String                    requestPath;
    private final String                    method;
    private final Map<String, List<String>> headers    = new LinkedHashMap<>();
    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private final Map<String, Object>       attributes = new LinkedHashMap<>();
    private final String                    queryString;
    private       byte[]                    body       = new byte[0];

    public MockServletRequest(String path, String method) {
        int idx = path.indexOf('?');
        if (idx >= 0) {
            this.requestPath = path.substring(0, idx);
            this.queryString = path.substring(idx + 1);
        } else {
            this.requestPath = path;
            this.queryString = null;
        }
        this.method = method;
    }

    @Override
    public String getMethod() {
        return this.method;
    }

    @Override
    public String getRequestURI() {
        if (this.queryString != null) {
            return this.requestPath + "?" + this.queryString;
        }
        return this.requestPath;
    }

    @Override
    public String getRequestPath() {
        return this.requestPath;
    }

    @Override
    public String getQueryString() {
        return this.queryString;
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public String getHeader(String name) {
        List<String> values = this.headers.get(name.toLowerCase());
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public List<String> getHeaders(String name) {
        List<String> values = this.headers.get(name.toLowerCase());
        return values != null ? values : Collections.emptyList();
    }

    @Override
    public Iterable<String> getHeaderNames() {
        return this.headers.keySet();
    }

    @Override
    public int getIntHeader(String name, int defaultValue) {
        String val = getHeader(name);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    @Override
    public String getContentType() {
        return getHeader("content-type");
    }

    @Override
    public long getContentLength() {
        String len = getHeader("content-length");
        return len != null ? Long.parseLong(len) : -1;
    }

    @Override
    public String getParameter(String name) {
        List<String> values = this.parameters.get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        return this.parameters.get(name);
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        return Collections.unmodifiableMap(this.parameters);
    }

    @Override
    public List<Cookie> getCookies() {
        return Collections.emptyList();
    }

    @Override
    public Cookie getCookie(String name) {
        return null;
    }

    @Override
    public ByteBuf getBody() {
        return ByteBuf.wrap(this.body);
    }

    public MockServletRequest setBody(byte[] body) {
        this.body = body;
        return this;
    }

    @Override
    public InputStream getBodyAsStream() {
        return new ByteArrayInputStream(this.body);
    }

    @Override
    public String getBodyAsString() {
        return new String(this.body);
    }

    @Override
    public String getBodyAsString(String charset) {
        try {
            return new String(this.body, charset);
        } catch (Exception e) {
            return new String(this.body);
        }
    }

    @Override
    public HttpSession getSession(boolean create) {
        return null;
    }

    @Override
    public HttpSession getSession() {
        return null;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 12345);
    }

    @Override
    public SocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 8080);
    }

    @Override
    public String getHost() {
        return "localhost";
    }

    @Override
    public int getPort() {
        return 8080;
    }

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        this.attributes.put(name, value);
    }

    // --- Builder methods ---

    @Override
    public void removeAttribute(String name) {
        this.attributes.remove(name);
    }

    public MockServletRequest addHeader(String name, String value) {
        this.headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
        return this;
    }

    public MockServletRequest addParameter(String name, String value) {
        this.parameters.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }
}
