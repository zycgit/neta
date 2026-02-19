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
package net.hasor.neta.http;
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
}
