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
import java.io.UnsupportedEncodingException;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufInputStream;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.multipart.MultipartDecoder;
import net.hasor.neta.http.HttpSession;
import net.hasor.neta.http.ServletRequest;
import net.hasor.neta.http.SessionManager;

/**
 * Default implementation of {@link ServletRequest} wrapping a neta {@link FullHttpRequest}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultServletRequest implements ServletRequest {
    private final FullHttpRequest     httpRequest;
    private final NetChannel          channel;
    private final boolean             secure;
    private final SessionManager      sessionManager;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    // Lazily parsed fields
    private String                    requestPath;
    private String                    queryString;
    private boolean                   pathParsed      = false;
    private Map<String, List<String>> parameterMap;
    private List<Cookie>              cookies;
    private HttpSession               session;
    private List<FileUpload>          fileUploads;
    private boolean                   multipartParsed = false;

    public DefaultServletRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager) {
        this.httpRequest = Objects.requireNonNull(httpRequest);
        this.channel = Objects.requireNonNull(channel);
        this.secure = secure;
        this.sessionManager = sessionManager;
    }

    private static void parseParams(String queryString, Map<String, List<String>> params) {
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            try {
                if (eq >= 0) {
                    key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } else {
                    key = URLDecoder.decode(pair, "UTF-8");
                    value = "";
                }
            } catch (UnsupportedEncodingException e) {
                key = pair.substring(0, eq >= 0 ? eq : pair.length());
                value = eq >= 0 ? pair.substring(eq + 1) : "";
            }
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    private static void parseCookieHeader(String headerValue, List<Cookie> result) {
        // Cookie header format: name1=value1; name2=value2
        if (headerValue == null || headerValue.isEmpty()) {
            return;
        }
        String[] parts = headerValue.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                result.add(new DefaultCookie(name, value));
            }
        }
    }

    @Override
    public String getMethod() {
        return this.httpRequest.method().name();
    }

    @Override
    public String getRequestURI() {
        return this.httpRequest.uri();
    }

    @Override
    public String getRequestPath() {
        parsePath();
        return this.requestPath;
    }

    @Override
    public String getQueryString() {
        parsePath();
        return this.queryString;
    }

    private void parsePath() {
        if (this.pathParsed) {
            return;
        }
        this.pathParsed = true;
        String uri = this.httpRequest.uri();
        int idx = uri.indexOf('?');
        if (idx >= 0) {
            this.requestPath = uri.substring(0, idx);
            this.queryString = uri.substring(idx + 1);
        } else {
            this.requestPath = uri;
            this.queryString = null;
        }
    }

    @Override
    public String getProtocol() {
        return this.httpRequest.protocolVersion().text();
    }

    // --- Headers ---

    @Override
    public String getScheme() {
        return this.secure ? "https" : "http";
    }

    @Override
    public boolean isSecure() {
        return this.secure;
    }

    @Override
    public String getHeader(String name) {
        return this.httpRequest.headers().get(name);
    }

    @Override
    public List<String> getHeaders(String name) {
        return this.httpRequest.headers().getAll(name);
    }

    @Override
    public Iterable<String> getHeaderNames() {
        return this.httpRequest.headers().names();
    }

    @Override
    public int getIntHeader(String name, int defaultValue) {
        return this.httpRequest.headers().getInt(name, defaultValue);
    }

    // --- Parameters ---

    @Override
    public String getContentType() {
        return this.httpRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public long getContentLength() {
        return this.httpRequest.headers().getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
    }

    @Override
    public String getParameter(String name) {
        List<String> values = getParameterValues(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    // --- Cookies ---

    @Override
    public Map<String, List<String>> getParameterMap() {
        if (this.parameterMap == null) {
            this.parameterMap = new LinkedHashMap<>();
            // parse query string
            String qs = getQueryString();
            if (qs != null && !qs.isEmpty()) {
                parseParams(qs, this.parameterMap);
            }
            // parse form body (application/x-www-form-urlencoded)
            String ct = getContentType();
            if (ct != null && ct.toLowerCase().contains(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)) {
                ByteBuf body = this.httpRequest.content();
                if (body != null && body.readableBytes() > 0) {
                    String formBody = body.getString(body.readerIndex(), body.readableBytes(), StandardCharsets.UTF_8);
                    parseParams(formBody, this.parameterMap);
                }
            }
            // also parse plain text fields from multipart/form-data
            if (isMultipart()) {
                for (FileUpload part : getFileUploads()) {
                    if (part.filename() == null) {
                        // plain form field (not a file)
                        String value = part.content().getString(part.content().readerIndex(), part.content().readableBytes(), StandardCharsets.UTF_8);
                        this.parameterMap.computeIfAbsent(part.name(), k -> new ArrayList<>()).add(value);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(this.parameterMap);
    }

    @Override
    public List<Cookie> getCookies() {
        if (this.cookies == null) {
            this.cookies = new ArrayList<>();
            List<String> cookieHeaders = this.httpRequest.headers().getAll(HttpHeaderNames.COOKIE);
            for (String headerValue : cookieHeaders) {
                parseCookieHeader(headerValue, this.cookies);
            }
        }
        return Collections.unmodifiableList(this.cookies);
    }

    @Override
    public Cookie getCookie(String name) {
        for (Cookie cookie : getCookies()) {
            if (cookie.name().equals(name)) {
                return cookie;
            }
        }
        return null;
    }

    // --- Multipart / File Upload ---

    @Override
    public boolean isMultipart() {
        String ct = getContentType();
        return ct != null && ct.toLowerCase().contains(HttpHeaderValues.MULTIPART_FORM_DATA);
    }

    @Override
    public List<FileUpload> getFileUploads() {
        parseMultipart();
        return this.fileUploads;
    }

    @Override
    public FileUpload getFileUpload(String fieldName) {
        for (FileUpload part : getFileUploads()) {
            if (part.name().equals(fieldName)) {
                return part;
            }
        }
        return null;
    }

    private void parseMultipart() {
        if (this.multipartParsed) {
            return;
        }
        this.multipartParsed = true;
        if (!isMultipart()) {
            this.fileUploads = Collections.emptyList();
            return;
        }
        String boundary = MultipartDecoder.extractBoundary(getContentType());
        if (boundary == null || boundary.isEmpty()) {
            this.fileUploads = Collections.emptyList();
            return;
        }
        ByteBuf body = this.httpRequest.content();
        if (body == null || body.readableBytes() == 0) {
            this.fileUploads = Collections.emptyList();
            return;
        }
        this.fileUploads = MultipartDecoder.decode(body, boundary);
    }

    // --- Body ---

    @Override
    public ByteBuf getBody() {
        return this.httpRequest.content();
    }

    @Override
    public InputStream getBodyAsStream() {
        ByteBuf content = this.httpRequest.content();
        if (content == null || content.readableBytes() == 0) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new ByteBufInputStream(content);
    }

    @Override
    public String getBodyAsString() {
        return getBodyAsString("UTF-8");
    }

    @Override
    public String getBodyAsString(String charset) {
        ByteBuf content = this.httpRequest.content();
        if (content == null || content.readableBytes() == 0) {
            return "";
        }
        return content.getString(content.readerIndex(), content.readableBytes(), Charset.forName(charset));
    }

    // --- Session ---

    @Override
    public HttpSession getSession(boolean create) {
        if (this.session != null && this.session.isValid()) {
            return this.session;
        }
        // try to find session from cookie
        Cookie sessionCookie = getCookie("NSESSIONID");
        if (sessionCookie != null) {
            this.session = this.sessionManager.getSession(sessionCookie.value());
            if (this.session != null && this.session.isValid()) {
                return this.session;
            }
        }
        // create if requested
        if (create) {
            this.session = this.sessionManager.createSession();
            return this.session;
        }
        return null;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    // --- Connection Info ---

    @Override
    public SocketAddress getRemoteAddress() {
        return this.channel.getRemoteAddr();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.channel.getLocalAddr();
    }

    @Override
    public String getHost() {
        String host = this.httpRequest.headers().get(HttpHeaderNames.HOST);
        if (host != null) {
            int colonIdx = host.indexOf(':');
            return colonIdx >= 0 ? host.substring(0, colonIdx) : host;
        }
        return null;
    }

    @Override
    public int getPort() {
        String host = this.httpRequest.headers().get(HttpHeaderNames.HOST);
        if (host != null) {
            int colonIdx = host.indexOf(':');
            if (colonIdx >= 0) {
                try {
                    return Integer.parseInt(host.substring(colonIdx + 1));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return this.secure ? 443 : 80;
    }

    // --- Attributes ---

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        this.attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        this.attributes.remove(name);
    }

    /** Returns the underlying neta FullHttpRequest */
    public FullHttpRequest getOriginalRequest() {
        return this.httpRequest;
    }

    /** Returns the underlying neta NetChannel */
    public NetChannel getChannel() {
        return this.channel;
    }
}
