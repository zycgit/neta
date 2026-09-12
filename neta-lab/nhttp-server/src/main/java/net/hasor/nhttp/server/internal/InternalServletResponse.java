/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import net.hasor.neta.codec.http.cookie.ServerCookieEncoder;
import net.hasor.nhttp.server.HttpSession;
import net.hasor.nhttp.server.ServletResponse;
import net.hasor.nhttp.server.connector.ResponseSink;

/**
 * {@link ServletResponse} implementation that writes through a {@link ResponseSink},
 * supporting both buffered (full) and streaming response modes.
 *
 * <h3>Buffered mode (default)</h3>
 * <p>All body writes accumulate in an in-memory buffer. When {@link #commit()} is called
 * (typically by the container after the servlet returns), the complete body is sent as a
 * single non-chunked response with a {@code Content-Length} header.</p>
 *
 * <h3>Streaming mode</h3>
 * <p>Streaming mode is entered when:
 * <ul>
 *   <li>{@link #startStreaming()} is called explicitly, or</li>
 *   <li>{@link OutputStream#flush()} is called on the stream returned by {@link #getOutputStream()}.</li>
 * </ul>
 * On transition, headers are sent immediately via
 * {@link ResponseSink#sendHeaders(int, net.hasor.neta.codec.http.HttpHeaders, boolean) sendHeaders(status, headers, true)}.
 * Any bytes that have already accumulated in the buffer are flushed as the first chunk.
 * Subsequent {@link #flushBuffer()} calls send the current buffer contents as additional chunks.
 * {@link #commit()} sends the terminal chunk (possibly empty) with {@code last=true}.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class InternalServletResponse implements ServletResponse {

    private final ResponseSink          responseSink;
    private final String                serverName;
    private final DefaultHttpHeaders    headers    = new DefaultHttpHeaders();
    private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream(256);

    private int         statusCode    = 200;
    private boolean     committed     = false;
    private boolean     streaming     = false;
    private boolean     terminated    = false;  // true once commit() has fully fired
    private String      contentType;
    private long        contentLength = -1;
    private HttpSession session;

    /**
     * Output stream returned to callers. Delegates to {@link #bodyBuffer}; its
     * {@link OutputStream#flush()} triggers {@link #flushBuffer()} (auto-start streaming).
     */
    private final OutputStream outputStream = new OutputStream() {
        @Override
        public void write(int b) {
            bodyBuffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bodyBuffer.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            flushBuffer();
        }
    };

    /**
     * @param responseSink protocol-aware response writer (HTTP/1.1 or HTTP/2)
     * @param serverName   value for the {@code Server} response header; {@code null} to omit
     */
    public InternalServletResponse(ResponseSink responseSink, String serverName) {
        this.responseSink = Objects.requireNonNull(responseSink);
        this.serverName = serverName;
    }

    // =========================================================================
    // Status
    // =========================================================================

    @Override
    public int getStatus() {
        return this.statusCode;
    }

    @Override
    public void setStatus(int statusCode) {
        checkNotCommitted();
        this.statusCode = statusCode;
    }

    // =========================================================================
    // Headers
    // =========================================================================

    @Override
    public void setHeader(String name, String value) {
        checkNotCommitted();
        this.headers.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        checkNotCommitted();
        this.headers.addHeader(name, value);
    }

    @Override
    public String getHeader(String name) {
        return this.headers.getString(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return this.headers.getValues(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return this.headers.headerNames();
    }

    @Override
    public boolean containsHeader(String name) {
        return this.headers.containsHeader(name);
    }

    @Override
    public String getContentType() {
        return this.contentType;
    }

    @Override
    public void setContentType(String type) {
        checkNotCommitted();
        this.contentType = type;
        this.headers.setHeader(HttpHeaderNames.CONTENT_TYPE, type);
    }

    @Override
    public void setContentLength(long length) {
        checkNotCommitted();
        this.contentLength = length;
    }

    // =========================================================================
    // Cookies
    // =========================================================================

    @Override
    public void addCookie(Cookie cookie) {
        checkNotCommitted();
        this.headers.addHeader(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.encode(cookie));
    }

    // =========================================================================
    // Body writes
    // =========================================================================

    @Override
    public OutputStream getOutputStream() {
        return this.outputStream;
    }

    @Override
    public void write(String content) throws IOException {
        if (content != null) {
            this.bodyBuffer.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void write(byte[] content) throws IOException {
        if (content != null) {
            this.bodyBuffer.write(content);
        }
    }

    @Override
    public void write(byte[] content, int offset, int length) throws IOException {
        if (content != null) {
            this.bodyBuffer.write(content, offset, length);
        }
    }

    // =========================================================================
    // Redirect & Error
    // =========================================================================

    @Override
    public void sendRedirect(String location) throws IOException {
        checkNotCommitted();
        this.statusCode = 302;
        this.headers.setHeader(HttpHeaderNames.LOCATION, location);
        commit();
    }

    @Override
    public void sendError(int statusCode) throws IOException {
        sendError(statusCode, HttpStatus.valueOf(statusCode).reasonPhrase());
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException {
        checkNotCommitted();
        this.statusCode = statusCode;
        this.bodyBuffer.reset();
        String errorBody = "<html><body><h1>" + statusCode + " " + htmlEscape(message) + "</h1></body></html>";
        this.bodyBuffer.write(errorBody.getBytes(StandardCharsets.UTF_8));
        this.headers.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
        commit();
    }

    // =========================================================================
    // State
    // =========================================================================

    @Override
    public boolean isCommitted() {
        return this.committed;
    }

    @Override
    public boolean isStreaming() {
        return this.streaming;
    }

    // =========================================================================
    // Streaming
    // =========================================================================

    /**
     * Switches this response to streaming mode and sends the HTTP response headers
     * immediately. Any bytes already in the body buffer are sent as the first data chunk.
     *
     * @throws IllegalStateException if the response has already been committed
     * @throws IOException           (declared; not thrown by current sinks)
     */
    @Override
    public void startStreaming() throws IOException {
        if (this.streaming) {
            return; // already streaming — no-op
        }
        checkNotCommitted();
        this.streaming = true;
        this.committed = true;

        injectServerHeaders();
        this.responseSink.sendHeaders(this.statusCode, this.headers, true);

        // flush any bytes already in the buffer as the first chunk
        flushBodyBuffer(false);
    }

    /**
     * Flushes the current body buffer to the client.
     * If not yet streaming, implicitly enters streaming mode first.
     *
     * @throws IOException (declared; not thrown by current sinks)
     */
    @Override
    public void flushBuffer() throws IOException {
        if (!this.streaming) {
            startStreaming();
            return; // startStreaming already flushed the buffer
        }
        flushBodyBuffer(false);
        this.responseSink.flush();
    }

    // =========================================================================
    // Commit (called by container after dispatch)
    // =========================================================================

    /**
     * Finalises the response.
     *
     * <ul>
     *   <li>If in streaming mode: flushes any remaining buffered bytes and sends the
     *       terminal (last) chunk, marking the end of the response body.</li>
     *   <li>If in buffered mode: injects standard headers, computes Content-Length,
     *       sends headers, and sends the entire body as a single chunk.</li>
     * </ul>
     *
     * <p>Idempotent: calling this method more than once is safe.</p>
     */
    public void commit() throws IOException {
        if (this.terminated) {
            return; // idempotent
        }

        if (this.streaming) {
            // streaming path — headers were already sent by startStreaming();
            // flush remaining bytes and send the terminal last chunk
            this.terminated = true;
            flushBodyBuffer(true);
            return;
        }

        // --- Buffered mode ---
        this.committed = true;
        this.terminated = true;

        byte[] body = this.bodyBuffer.toByteArray();
        injectServerHeaders();

        long len = (this.contentLength >= 0) ? this.contentLength : body.length;
        this.headers.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(len));

        this.responseSink.sendHeaders(this.statusCode, this.headers, false);

        ByteBuf content = (body.length > 0) ? ByteBuf.wrap(body) : null;
        this.responseSink.sendContent(content, true);
    }

    // =========================================================================
    // Container accessors
    // =========================================================================

    /** Associates the resolved session with this response (used for session cookie injection). */
    public void setSession(HttpSession session) {
        this.session = session;
    }

    /**
     * Returns the mutable response-header map.
     *
     * <p>Intended for use by the container layer only (e.g. to pass to
     * {@link net.hasor.neta.codec.http.cors.CorsUtil} overloads that accept
     * {@link net.hasor.neta.codec.http.HttpHeaders} directly). Must not be called
     * after the response has been committed.</p>
     */
    public DefaultHttpHeaders getResponseHeaders() {
        return this.headers;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Sends accumulated body buffer bytes as a content chunk. */
    private void flushBodyBuffer(boolean last) {
        byte[] bytes = this.bodyBuffer.toByteArray();
        this.bodyBuffer.reset();
        ByteBuf content = (bytes.length > 0) ? ByteBuf.wrap(bytes) : null;
        this.responseSink.sendContent(content, last);
    }

    /** Injects {@code Server} header and session cookie if not already present. */
    private void injectServerHeaders() {
        // Server header
        if (this.serverName != null && !this.headers.containsHeader(HttpHeaderNames.SERVER)) {
            this.headers.setHeader(HttpHeaderNames.SERVER, this.serverName);
        }
        // Session cookie — only for new sessions
        if (this.session != null && this.session.isNew()) {
            DefaultCookie sessionCookie = new DefaultCookie("NSESSIONID", this.session.getId());
            sessionCookie.setPath("/");
            sessionCookie.setHttpOnly(true);
            this.headers.addHeader(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.encode(sessionCookie));
        }
    }

    private void checkNotCommitted() {
        if (this.committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
