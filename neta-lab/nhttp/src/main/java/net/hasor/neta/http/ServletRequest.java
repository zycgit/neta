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
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.multipart.FileUpload;

/**
 * Servlet-like HTTP request interface. Wraps a FullHttpRequest from the neta HTTP codec.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ServletRequest {

    // --- Request Line ---

    /** Returns the HTTP method (GET, POST, etc.) */
    String getMethod();

    /** Returns the full request URI including query string (e.g., "/path?key=value") */
    String getRequestURI();

    /** Returns only the path part of the URI (without query string) */
    String getRequestPath();

    /** Returns the query string portion of the URI, or null if none */
    String getQueryString();

    /** Returns the HTTP protocol version string (e.g., "HTTP/1.1") */
    String getProtocol();

    /** Returns the scheme ("http" or "https") */
    String getScheme();

    /** Returns true if the request was made over HTTPS */
    boolean isSecure();

    // --- Headers ---

    /** Returns the value of the specified header, or null */
    String getHeader(String name);

    /** Returns all values for the specified header */
    List<String> getHeaders(String name);

    /** Returns all header names */
    Iterable<String> getHeaderNames();

    /** Returns the integer value of a header, or defaultValue if not present/parsable */
    int getIntHeader(String name, int defaultValue);

    /** Returns the Content-Type header value, or null */
    String getContentType();

    /** Returns the Content-Length, or -1 if not set */
    long getContentLength();

    // --- Parameters ---

    /** Returns the value of a request parameter (query string or form body) */
    String getParameter(String name);

    /** Returns all values for a given parameter name */
    List<String> getParameterValues(String name);

    /** Returns a map of all parameter names to their values */
    Map<String, List<String>> getParameterMap();

    // --- Cookies ---

    /** Returns all cookies sent with this request */
    List<Cookie> getCookies();

    /** Returns the first cookie with the given name, or null */
    Cookie getCookie(String name);

    // --- Multipart / File Upload ---

    /** Returns true if the request is a multipart/form-data request */
    boolean isMultipart();

    /** Returns the parsed file upload parts from a multipart/form-data request, or empty list if not multipart */
    List<FileUpload> getFileUploads();

    /** Returns the first file upload part with the given field name, or null */
    FileUpload getFileUpload(String fieldName);

    // --- Body ---

    /** Returns the request body as a ByteBuf */
    ByteBuf getBody();

    /** Returns the request body as an InputStream */
    InputStream getBodyAsStream();

    /** Returns the request body as a String using UTF-8 */
    String getBodyAsString();

    /** Returns the request body as a String using the specified charset */
    String getBodyAsString(String charset);

    // --- Session ---

    /** Returns the current session, creating one if create is true */
    HttpSession getSession(boolean create);

    /** Returns the current session, creating one if it doesn't exist */
    HttpSession getSession();

    // --- Connection Info ---

    /** Returns the remote address of the client */
    SocketAddress getRemoteAddress();

    /** Returns the local address the server is listening on */
    SocketAddress getLocalAddress();

    /** Returns the Host header value */
    String getHost();

    /** Returns the port from the Host header, or the default port */
    int getPort();

    // --- Attributes (request-scoped storage) ---

    /** Returns a request attribute, or null */
    Object getAttribute(String name);

    /** Sets a request attribute */
    void setAttribute(String name, Object value);

    /** Removes a request attribute */
    void removeAttribute(String name);
}
