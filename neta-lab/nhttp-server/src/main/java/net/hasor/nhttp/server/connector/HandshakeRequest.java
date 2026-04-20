/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.connector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeEvent;
import net.hasor.nhttp.server.HttpSession;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.SessionManager;

/**
 * A read-only {@link ServletRequest} adapter for the WebSocket handshake upgrade request.
 *
 * <p>Created by the container layer when processing
 * {@link RequestDispatchCallback#onWebSocketOpen} and passed to the application's
 * {@link net.hasor.nhttp.server.WebSocketHandler} via
 * {@link net.hasor.nhttp.server.WebSocketSession#getUpgradeRequest()}.</p>
 *
 * <p>Only the request metadata available from the {@link WebSocketHandshakeEvent}
 * is exposed: URI, Host, Origin, and query parameters. Body methods return empty
 * results; file upload and multipart are not supported.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class HandshakeRequest implements ServletRequest {
    private final WebSocketHandshakeEvent handshakeEvent;
    private final NetChannel              channel;
    private final boolean                 secure;
    private final SessionManager          sessionManager;
    private final Map<String, Object>     attributes = new LinkedHashMap<>();
    private Map<String, List<String>>     parameterMap;
    private HttpSession                   session;

    public HandshakeRequest(WebSocketHandshakeEvent handshakeEvent, NetChannel channel, boolean secure, SessionManager sessionManager) {
        this.handshakeEvent = handshakeEvent;
        this.channel = channel;
        this.secure = secure;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getMethod() {
        return "GET";
    }

    @Override
    public String getRequestURI() {
        return this.handshakeEvent.requestPath();
    }

    @Override
    public String getRequestPath() {
        String uri = getRequestURI();
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    @Override
    public String getQueryString() {
        String uri = getRequestURI();
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(q + 1) : null;
    }

    @Override
    public String getProtocol() {
        return this.handshakeEvent.streamId() > 0 ? HttpVersion.HTTP_2_0.text() : HttpVersion.HTTP_1_1.text();
    }

    @Override
    public String getScheme() {
        return this.secure ? "wss" : "ws";
    }

    @Override
    public boolean isSecure() {
        return this.secure;
    }

    @Override
    public String getHeader(String name) {
        if (HttpHeaderNames.HOST.equalsIgnoreCase(name)) {
            return this.handshakeEvent.requestHost();
        }
        if (HttpHeaderNames.ORIGIN.equalsIgnoreCase(name)) {
            return this.handshakeEvent.requestOrigin();
        }
        return null;
    }

    @Override
    public List<String> getHeaders(String name) {
        String value = getHeader(name);
        return value == null ? Collections.emptyList() : Collections.singletonList(value);
    }

    @Override
    public Iterable<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        if (this.handshakeEvent.requestHost() != null) {
            names.add(HttpHeaderNames.HOST);
        }
        if (this.handshakeEvent.requestOrigin() != null) {
            names.add(HttpHeaderNames.ORIGIN);
        }
        return names;
    }

    @Override
    public int getIntHeader(String name, int defaultValue) {
        String value = getHeader(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public long getContentLength() {
        return 0;
    }

    @Override
    public String getParameter(String name) {
        List<String> values = getParameterValues(name);
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<String> getParameterValues(String name) {
        List<String> values = getParameterMap().get(name);
        return values == null ? Collections.emptyList() : values;
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        if (this.parameterMap == null) {
            this.parameterMap = new LinkedHashMap<>();
            String qs = getQueryString();
            if (qs != null && !qs.isEmpty()) {
                for (String pair : qs.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    String key = decodeUrl(eq >= 0 ? pair.substring(0, eq) : pair);
                    String val = decodeUrl(eq >= 0 ? pair.substring(eq + 1) : "");
                    this.parameterMap.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
                }
            }
        }
        return Collections.unmodifiableMap(this.parameterMap);
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
    public boolean isMultipart() {
        return false;
    }

    @Override
    public List<FileUpload> getFileUploads() {
        return Collections.emptyList();
    }

    @Override
    public FileUpload getFileUpload(String fieldName) {
        return null;
    }

    @Override
    public ByteBuf getBody() {
        return ByteBuf.EMPTY;
    }

    @Override
    public InputStream getBodyAsStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String getBodyAsString() {
        return "";
    }

    @Override
    public String getBodyAsString(String charset) {
        return "";
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (this.session != null && this.session.isValid()) {
            return this.session;
        }
        if (this.sessionManager == null) {
            return null;
        }
        // WebSocket upgrades arrive without a fresh cookie; session lookup only
        if (!create) {
            return null;
        }
        this.session = this.sessionManager.createSession();
        return this.session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

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
        String host = this.handshakeEvent.requestHost();
        if (host == null) {
            return null;
        }
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    @Override
    public int getPort() {
        String host = this.handshakeEvent.requestHost();
        if (host != null) {
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && colon < host.length() - 1) {
                try {
                    return Integer.parseInt(host.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return this.secure ? 443 : 80;
    }

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            this.attributes.remove(name);
        } else {
            this.attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        this.attributes.remove(name);
    }

    private static String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }
}
