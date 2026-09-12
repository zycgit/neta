/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import net.hasor.neta.codec.http.cookie.Cookie;

/**
 * Servlet-like HTTP response interface. Wraps a FullHttpResponse to be sent through neta pipeline.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ServletResponse {

    // --- Status ---

    /** Returns the current status code */
    int getStatus();

    /** Sets the HTTP status code */
    void setStatus(int statusCode);

    // --- Headers ---

    /** Sets a response header (replaces existing values) */
    void setHeader(String name, String value);

    /** Adds a response header value (appends) */
    void addHeader(String name, String value);

    /** Returns the value of a response header */
    String getHeader(String name);

    /** Returns all values for a response header */
    Collection<String> getHeaders(String name);

    /** Returns all response header names */
    Collection<String> getHeaderNames();

    /** Returns true if the header has been set */
    boolean containsHeader(String name);

    /** Returns the Content-Type */
    String getContentType();

    /** Sets the Content-Type header */
    void setContentType(String type);

    /** Sets the Content-Length header */
    void setContentLength(long length);

    // --- Cookies ---

    /** Adds a Set-Cookie header */
    void addCookie(Cookie cookie);

    // --- Body ---

    /** Returns an OutputStream for writing response body */
    OutputStream getOutputStream();

    /** Writes a string to the response body using UTF-8 */
    void write(String content) throws IOException;

    /** Writes bytes to the response body */
    void write(byte[] content) throws IOException;

    /** Writes a portion of bytes to the response body */
    void write(byte[] content, int offset, int length) throws IOException;

    // --- Redirect & Error ---

    /** Sends a redirect response (302) to the specified URL */
    void sendRedirect(String location) throws IOException;

    /** Sends an error response with the given status code */
    void sendError(int statusCode) throws IOException;

    /** Sends an error response with the given status code and message */
    void sendError(int statusCode, String message) throws IOException;

    // --- State ---

    /** Returns true if the response has been committed (headers sent) */
    boolean isCommitted();

    // --- Streaming ---

    /**
     * Switches the response to streaming mode, sending HTTP headers immediately.
     * Subsequent writes are flushed as chunked data (HTTP/1.1) or DATA frames (HTTP/2).
     *
     * <p>This method is optional: streaming mode is also entered automatically on the
     * first call to {@code getOutputStream().flush()}. Call this explicitly when you need
     * to send headers before writing any body bytes (e.g. SSE, long-polling).</p>
     *
     * @throws IOException if headers could not be sent
     * @throws IllegalStateException if the response has already been committed
     */
    default void startStreaming() throws IOException {
        throw new UnsupportedOperationException("Streaming is not supported in this context.");
    }

    /**
     * Flushes the current response buffer, sending buffered bytes to the client.
     * If not yet in streaming mode, automatically switches to it on the first flush.
     *
     * @throws IOException if the flush fails
     */
    default void flushBuffer() throws IOException {
        throw new UnsupportedOperationException("Streaming is not supported in this context.");
    }

    /** Returns {@code true} if this response is currently in streaming mode. */
    default boolean isStreaming() {
        return false;
    }
}
