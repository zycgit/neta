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
package net.hasor.nhttp.server.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufInputStream;
import net.hasor.neta.bytebuf.ByteBufOutputStream;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.multipart.MultipartDecoder;
import net.hasor.nhttp.server.*;
import net.hasor.nhttp.server.connector.BackpressureStrategy;

/**
 * {@link ServletRequest} implementation for the streaming HTTP model.
 *
 * <p>This class holds the request line and headers separately from the body. The body is read
 * on demand from an {@link InternalBodyChannel}: the first call to any body accessor method
 * ({@link #getBody()}, {@link #getBodyAsString()}, {@link #getParameterMap()} for form data,
 * {@link #getFileUploads()}) triggers a blocking drain of all pending body chunks on the
 * current (worker) thread. The drained bytes are cached for subsequent calls.</p>
 *
 * <h3>Body-drain timeout</h3>
 * <p>Each chunk read attempt uses {@code chunkReadTimeoutMillis} as the per-chunk wait.
 * If a chunk does not arrive in time, reading stops and the accumulated bytes are returned
 * as the partial body.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class StreamingServletRequest implements ServletRequest {
    private static final Logger logger = Logger.getLogger(StreamingServletRequest.class);

    public static StreamingServletRequest fromFullHttpRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager) {
        return fromFullHttpRequest(httpRequest, channel, secure, sessionManager, 30_000L);
    }

    public static StreamingServletRequest fromFullHttpRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager, long chunkReadTimeoutMillis) {
        InternalBodyChannel bodyChannel = new InternalBodyChannel(1, BackpressureStrategy.FAST_FAIL, channel);
        ByteBuf body = httpRequest.content();
        bodyChannel.offer(new DefaultLastHttpContent(body != null ? body.copy() : ByteBuf.EMPTY));
        return new StreamingServletRequest(httpRequest, httpRequest, bodyChannel, channel, secure, sessionManager, chunkReadTimeoutMillis);
    }

    private final HttpRequest         requestLine;
    private final HttpHeaders         requestHeaders;
    private final InternalBodyChannel bodyChannel;
    private final NetChannel          channel;
    private final boolean             secure;
    private final SessionManager      sessionManager;
    private final long                chunkReadTimeoutMillis;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    // Lazily parsed / computed fields
    private String                    cachedPath;
    private String                    cachedQueryString;
    private boolean                   pathParsed      = false;
    private Map<String, List<String>> parameterMap;
    private List<Cookie>              cookies;
    private HttpSession               session;
    private ByteBuf                   body;           // null until drained
    private boolean                   bodyDrained     = false;
    private List<FileUpload>          fileUploads;
    private boolean                   multipartParsed = false;
    private boolean                   released        = false;

    // Async processing support
    private Supplier<InternalAsyncContext> asyncContextSupplier; // injected by container before dispatch
    private InternalAsyncContext           asyncContext;          // non-null after startAsync()
    private DispatcherType                 dispatcherType = DispatcherType.NORMAL;
    private String                         asyncDispatchPath;     // non-null during async dispatch

    /**
     * @param requestLine           HTTP request line (method / URI / version)
     * @param requestHeaders        all accumulated request headers
     * @param bodyChannel           streaming body source
     * @param channel               underlying network channel
     * @param secure                {@code true} if TLS
     * @param sessionManager        session manager
     * @param chunkReadTimeoutMillis maximum wait per body chunk (ms); 0 = use 30 s default
     */
    public StreamingServletRequest(HttpRequest requestLine, HttpHeaders requestHeaders, InternalBodyChannel bodyChannel, NetChannel channel, boolean secure, SessionManager sessionManager, long chunkReadTimeoutMillis) {
        this.requestLine = Objects.requireNonNull(requestLine);
        this.requestHeaders = Objects.requireNonNull(requestHeaders);
        this.bodyChannel = Objects.requireNonNull(bodyChannel);
        this.channel = Objects.requireNonNull(channel);
        this.secure = secure;
        this.sessionManager = sessionManager;
        this.chunkReadTimeoutMillis = (chunkReadTimeoutMillis > 0) ? chunkReadTimeoutMillis : 30_000L;
    }

    // =========================================================================
    // Request line
    // =========================================================================

    @Override
    public String getMethod() {
        return this.requestLine.method().name();
    }

    @Override
    public String getRequestURI() {
        return this.requestLine.uri();
    }

    @Override
    public String getRequestPath() {
        // During an async dispatch the container may override the path (e.g. AsyncContext.dispatch("/new"))
        if (this.asyncDispatchPath != null) {
            return this.asyncDispatchPath;
        }
        parsePath();
        return this.cachedPath;
    }

    @Override
    public String getQueryString() {
        parsePath();
        return this.cachedQueryString;
    }

    private void parsePath() {
        if (this.pathParsed) {
            return;
        }
        this.pathParsed = true;
        String uri = this.requestLine.uri();
        int idx = uri.indexOf('?');
        if (idx >= 0) {
            this.cachedPath = uri.substring(0, idx);
            this.cachedQueryString = uri.substring(idx + 1);
        } else {
            this.cachedPath = uri;
            this.cachedQueryString = null;
        }
    }

    @Override
    public String getProtocol() {
        return this.requestLine.protocolVersion().text();
    }

    @Override
    public String getScheme() {
        return this.secure ? "https" : "http";
    }

    @Override
    public boolean isSecure() {
        return this.secure;
    }

    // =========================================================================
    // Headers
    // =========================================================================

    @Override
    public String getHeader(String name) {
        return this.requestHeaders.getString(name);
    }

    @Override
    public List<String> getHeaders(String name) {
        return this.requestHeaders.getValues(name);
    }

    @Override
    public Iterable<String> getHeaderNames() {
        return this.requestHeaders.headerNames();
    }

    @Override
    public int getIntHeader(String name, int defaultValue) {
        return this.requestHeaders.getInt(name, defaultValue);
    }

    @Override
    public String getContentType() {
        return this.requestHeaders.getString(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public long getContentLength() {
        return this.requestHeaders.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
    }

    // =========================================================================
    // Parameters
    // =========================================================================

    @Override
    public String getParameter(String name) {
        List<String> values = getParameterValues(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        if (this.parameterMap == null) {
            this.parameterMap = new LinkedHashMap<>();
            // Query-string parameters
            String qs = getQueryString();
            if (qs != null && !qs.isEmpty()) {
                parseParams(qs, this.parameterMap);
            }
            // Form body (application/x-www-form-urlencoded)
            String ct = getContentType();
            if (ct != null && ct.toLowerCase().contains(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)) {
                ByteBuf buf = getBody();
                if (buf != null && buf.readableBytes() > 0) {
                    String formBody = buf.getString(buf.readerIndex(), buf.readableBytes(), StandardCharsets.UTF_8);
                    parseParams(formBody, this.parameterMap);
                }
            }
            // Plain text fields from multipart
            if (isMultipart()) {
                for (FileUpload part : getFileUploads()) {
                    if (part.filename() == null) {
                        String value = part.content().getString(part.content().readerIndex(), part.content().readableBytes(), StandardCharsets.UTF_8);
                        this.parameterMap.computeIfAbsent(part.name(), k -> new ArrayList<>()).add(value);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(this.parameterMap);
    }

    // =========================================================================
    // Cookies
    // =========================================================================

    @Override
    public List<Cookie> getCookies() {
        if (this.cookies == null) {
            this.cookies = new ArrayList<>();
            List<String> cookieHeaders = this.requestHeaders.getValues(HttpHeaderNames.COOKIE);
            for (String hv : cookieHeaders) {
                parseCookieHeader(hv, this.cookies);
            }
        }
        return Collections.unmodifiableList(this.cookies);
    }

    @Override
    public Cookie getCookie(String name) {
        for (Cookie c : getCookies()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // =========================================================================
    // Multipart
    // =========================================================================

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
        ByteBuf buf = getBody();
        if (buf == null || buf.readableBytes() == 0) {
            this.fileUploads = Collections.emptyList();
            return;
        }
        this.fileUploads = MultipartDecoder.decode(buf, boundary);
    }

    // =========================================================================
    // Body
    // =========================================================================

    /**
     * Returns the fully-drained request body as a {@link ByteBuf}.
     *
     * <p>On the first call this blocks the worker thread, reading chunks from
     * {@link InternalBodyChannel} until the body is complete or a per-chunk timeout expires.
     * The result is cached for subsequent calls. Returns {@code null} / zero-readable-bytes
     * when there is no body.</p>
     */
    @Override
    public ByteBuf getBody() {
        drainBodyIfNeeded();
        return this.body;
    }

    @Override
    public InputStream getBodyAsStream() {
        ByteBuf buf = getBody();
        if (buf == null || buf.readableBytes() == 0) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        return new ByteBufInputStream(buf);
    }

    @Override
    public String getBodyAsString() {
        return getBodyAsString("UTF-8");
    }

    @Override
    public String getBodyAsString(String charset) {
        ByteBuf buf = getBody();
        if (buf == null || buf.readableBytes() == 0) {
            return "";
        }
        return buf.getString(buf.readerIndex(), buf.readableBytes(), Charset.forName(charset));
    }

    /**
     * Drains and releases the remaining request body without caching it.
     * Used when the servlet never consumed the body but the container must
     * still read through the stream before completing the request.
     */
    public void discardUnreadBody() {
        if (this.bodyDrained) {
            return;
        }
        this.bodyDrained = true;

        try {
            while (!this.bodyChannel.isComplete()) {
                HttpContent chunk = this.bodyChannel.read(this.chunkReadTimeoutMillis, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    break;
                }
                chunk.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Body discard interrupted for channel " + this.channel.getChannelId());
        }
        this.body = null;
    }

    /**
     * Releases cached request resources and closes the underlying body channel.
     * Safe to call multiple times.
     */
    public void release() {
        if (this.released) {
            return;
        }
        this.released = true;

        this.bodyChannel.close();

        if (this.fileUploads != null) {
            for (FileUpload fileUpload : this.fileUploads) {
                if (fileUpload != null) {
                    fileUpload.release();
                }
            }
            this.fileUploads = Collections.emptyList();
        }

        if (this.body != null) {
            this.body.release();
            this.body = null;
        }
    }

    /**
     * Drains all body chunks from {@link InternalBodyChannel} into {@link #body}.
     * Blocking; must be called from the worker thread.
     */
    private void drainBodyIfNeeded() {
        if (this.bodyDrained) {
            return;
        }
        this.bodyDrained = true;

        ByteBuf bodyBuffer = ByteBufAllocator.DEFAULT.swapFile();
        ByteBufOutputStream out = new ByteBufOutputStream(bodyBuffer);
        try {
            while (!this.bodyChannel.isComplete()) {
                HttpContent chunk = this.bodyChannel.read(this.chunkReadTimeoutMillis, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    break; // timeout or channel closed
                }
                try {
                    ByteBuf content = chunk.content();
                    if (content != null && content.readableBytes() > 0) {
                        byte[] bytes = new byte[content.readableBytes()];
                        content.readBytes(bytes, 0, bytes.length);
                        out.write(bytes);
                    }
                } catch (IOException e) {
                    logger.warn("Error writing body chunk", e);
                    break;
                } finally {
                    chunk.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Body drain interrupted for channel " + this.channel.getChannelId());
        }
        if (out.writtenBytes() > 0) {
            out.buffer().markWriter();
            this.body = out.buffer();
        } else {
            bodyBuffer.free();
            this.body = null;
        }
    }

    // =========================================================================
    // Session
    // =========================================================================

    @Override
    public HttpSession getSession(boolean create) {
        if (this.session != null && this.session.isValid()) {
            return this.session;
        }
        Cookie sessionCookie = getCookie("NSESSIONID");
        if (sessionCookie != null) {
            this.session = this.sessionManager.getSession(sessionCookie.value());
            if (this.session != null && this.session.isValid()) {
                return this.session;
            }
        }
        if (create) {
            this.session = this.sessionManager.createSession();
        }
        return this.session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    // =========================================================================
    // Connection info
    // =========================================================================

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
        String host = this.requestHeaders.getString(HttpHeaderNames.HOST);
        if (host == null) {
            return null;
        }
        int colon = host.indexOf(':');
        return (colon >= 0) ? host.substring(0, colon) : host;
    }

    @Override
    public int getPort() {
        String host = this.requestHeaders.getString(HttpHeaderNames.HOST);
        if (host != null) {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                try {
                    return Integer.parseInt(host.substring(colon + 1));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return this.secure ? 443 : 80;
    }

    // =========================================================================
    // Attributes
    // =========================================================================

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

    // =========================================================================
    // Async processing
    // =========================================================================

    /**
     * Injects the factory that creates the {@link InternalAsyncContext} for this request.
     * Must be called by the container (i.e. {@code NetaHttpServer.handleRequest}) before
     * the request is dispatched to the servlet.
     */
    public void setAsyncContextSupplier(Supplier<InternalAsyncContext> supplier) {
        this.asyncContextSupplier = supplier;
    }

    /**
     * Clears the current async context so that {@link #isAsyncStarted()} returns {@code false}.
     * Called by the container before re-dispatching an async request; if the re-dispatch
     * servlet calls {@link #startAsync()} again a fresh context is created via the supplier.
     */
    public void resetAsyncState() {
        this.asyncContext = null;
    }

    /**
     * Sets the dispatcher type for async re-dispatches.
     * Called by the container when submitting an async dispatch.
     */
    public void setDispatcherType(DispatcherType type) {
        this.dispatcherType = type;
    }

    /**
     * Overrides the path returned by {@link #getRequestPath()} during an async dispatch.
     * When {@code null} the original request URI path is used.
     */
    public void setAsyncDispatchPath(String path) {
        this.asyncDispatchPath = path;
        this.pathParsed = false; // force re-parse on next getRequestPath() call
    }

    @Override
    public AsyncContext startAsync() {
        if (this.asyncContextSupplier == null) {
            throw new UnsupportedOperationException("Async processing is not supported in this context.");
        }
        if (this.asyncContext == null) {
            this.asyncContext = this.asyncContextSupplier.get();
        } else {
            // Re-entry: notify existing listeners then return the same context
            this.asyncContext.fireOnStartAsync();
        }
        return this.asyncContext;
    }

    @Override
    public boolean isAsyncStarted() {
        return this.asyncContext != null;
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (this.asyncContext == null) {
            throw new IllegalStateException("startAsync() has not been called on this request.");
        }
        return this.asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return this.dispatcherType;
    }

    // =========================================================================
    // Accessors for container internals
    // =========================================================================

    /** Returns the current session if one has been resolved, or {@code null}. */
    public HttpSession getResolvedSession() {
        return this.session;
    }

    /** Returns the underlying network channel. */
    public NetChannel getChannel() {
        return this.channel;
    }

    // =========================================================================
    // Static helpers
    // =========================================================================

    private static void parseParams(String query, Map<String, List<String>> out) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key, value;
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
                value = (eq >= 0) ? pair.substring(eq + 1) : "";
            }
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    private static void parseCookieHeader(String headerValue, List<Cookie> out) {
        if (headerValue == null || headerValue.isEmpty()) {
            return;
        }
        for (String part : headerValue.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.add(new DefaultCookie(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim()));
            }
        }
    }
}
