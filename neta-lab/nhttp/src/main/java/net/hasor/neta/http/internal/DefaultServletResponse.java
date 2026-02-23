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
import java.util.Collection;
import java.util.Objects;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import net.hasor.neta.codec.http.cookie.ServerCookieEncoder;
import net.hasor.neta.http.HttpSession;
import net.hasor.neta.http.ServletResponse;

/**
 * Default implementation of {@link ServletResponse}.
 * Buffers the response body and builds a FullHttpResponse when committed.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultServletResponse implements ServletResponse {
    private final NetChannel            channel;
    private final HttpHeaders           headers       = new HttpHeaders();
    private final ByteArrayOutputStream bodyBuffer    = new ByteArrayOutputStream(256);
    private       int                   statusCode    = 200;
    private       boolean               committed     = false;
    private       String                contentType;
    private       long                  contentLength = -1;
    private       HttpSession           session;

    public DefaultServletResponse(NetChannel channel) {
        this.channel = Objects.requireNonNull(channel);
    }

    // --- Status ---

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Override
    public int getStatus() {
        return this.statusCode;
    }

    // --- Headers ---

    @Override
    public void setStatus(int statusCode) {
        checkCommitted();
        this.statusCode = statusCode;
    }

    @Override
    public void setHeader(String name, String value) {
        checkCommitted();
        this.headers.set(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        checkCommitted();
        this.headers.add(name, value);
    }

    @Override
    public String getHeader(String name) {
        return this.headers.get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return this.headers.getAll(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return this.headers.names();
    }

    @Override
    public boolean containsHeader(String name) {
        return this.headers.contains(name);
    }

    @Override
    public String getContentType() {
        return this.contentType;
    }

    @Override
    public void setContentType(String type) {
        checkCommitted();
        this.contentType = type;
        this.headers.set(HttpHeaderNames.CONTENT_TYPE, type);
    }

    // --- Cookies ---

    @Override
    public void setContentLength(long length) {
        checkCommitted();
        this.contentLength = length;
    }

    // --- Body ---

    @Override
    public void addCookie(Cookie cookie) {
        checkCommitted();
        String encoded = ServerCookieEncoder.encode(cookie);
        this.headers.add(HttpHeaderNames.SET_COOKIE, encoded);
    }

    @Override
    public OutputStream getOutputStream() {
        return this.bodyBuffer;
    }

    @Override
    public void write(String content) throws IOException {
        if (content != null) {
            write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void write(byte[] content) throws IOException {
        if (content != null) {
            this.bodyBuffer.write(content);
        }
    }

    // --- Redirect & Error ---

    @Override
    public void write(byte[] content, int offset, int length) throws IOException {
        if (content != null) {
            this.bodyBuffer.write(content, offset, length);
        }
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        checkCommitted();
        this.statusCode = 302;
        this.headers.set(HttpHeaderNames.LOCATION, location);
        commit();
    }

    @Override
    public void sendError(int statusCode) throws IOException {
        sendError(statusCode, HttpStatus.valueOf(statusCode).reasonPhrase());
    }

    // --- State ---

    @Override
    public void sendError(int statusCode, String message) throws IOException {
        checkCommitted();
        this.statusCode = statusCode;
        this.bodyBuffer.reset();
        String errorBody = "<html><body><h1>" + statusCode + " " + htmlEscape(message) + "</h1></body></html>";
        this.bodyBuffer.write(errorBody.getBytes(StandardCharsets.UTF_8));
        this.headers.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
        commit();
    }

    @Override
    public boolean isCommitted() {
        return this.committed;
    }

    /** Associates a session with this response (for session cookie writing) */
    public void setSession(HttpSession session) {
        this.session = session;
    }

    /**
     * Commits the response by building a FullHttpResponse and sending it through the channel.
     */
    public void commit() throws IOException {
        if (this.committed) {
            return;
        }
        this.committed = true;

        byte[] body = this.bodyBuffer.toByteArray();
        ByteBuf content = (body.length > 0) ? ByteBuf.wrap(body) : null;

        HttpStatus status = HttpStatus.valueOf(this.statusCode);
        FullHttpResponse response;
        if (content != null) {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        }

        // copy headers
        response.headers().add(this.headers);

        // set content-length
        if (this.contentLength >= 0) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(this.contentLength));
        } else {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
        }

        // session cookie
        if (this.session != null && this.session.isNew()) {
            DefaultCookie sessionCookie = new DefaultCookie("NSESSIONID", this.session.getId());
            sessionCookie.setPath("/");
            sessionCookie.setHttpOnly(true);
            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.encode(sessionCookie));
        }

        // server header
        if (!response.headers().contains(HttpHeaderNames.SERVER)) {
            response.headers().set(HttpHeaderNames.SERVER, "Neta-HTTP/1.0");
        }

        this.channel.sendData(response);
    }

    private void checkCommitted() {
        if (this.committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }
}
