/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.*;

/**
 * Writes a {@link Request} as a HTTP/1.x style object stream.
 * <p>
 * The writer follows a small set of HTTP/1.x serialization policies so the outbound
 * object sequence remains deterministic for both builder callers and tests:
 * <ul>
 * <li>Always emit request line, then headers, then one or more content objects.</li>
 * <li>Auto-populate {@code Host} and {@code Content-Type} only when the caller did not set them.</li>
 * <li>Auto-populate {@code Content-Length} whenever the prepared body length is known.</li>
 * <li>Only auto-populate {@code Transfer-Encoding: chunked} for HTTP/1.1+ when no content length is known.</li>
 * <li>Only auto-populate {@code Connection: keep-alive} for versions whose default persistence is keep-alive.</li>
 * </ul>
 * This keeps HTTP/1.0 behavior conservative while still allowing HTTP/1.1 requests to use
 * the common persistent-connection and chunked-transfer defaults defined by RFC 9112.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpObjectWriter implements HttpWriter {
    /**
     * Serializes the request into an HTTP/1.x object stream.
     * <p>
     * During this step the writer applies the RFC-facing rules that are implicit in the higher-level
     * request model:
     * <ul>
     * <li>The request-target is emitted in origin-form via {@link Request#requestTarget()}.
     * This matches RFC 9112 Section 3.2 for ordinary client requests.</li>
     * <li>If the caller did not set {@code Host}, the writer derives it from the request URI so
     * HTTP/1.1 messages satisfy the mandatory host field requirement from RFC 9112.</li>
     * <li>If the body length is known, the writer emits {@code Content-Length} per RFC 9112
     * Section 6.2 unless the caller already supplied framing headers.</li>
     * <li>If the body length is unknown and the version is HTTP/1.1+, the writer emits
     * {@code Transfer-Encoding: chunked} per RFC 9112 Section 7.1 unless the caller already
     * chose another framing strategy.</li>
     * <li>For versions whose default persistence is keep-alive, the writer adds
     * {@code Connection: keep-alive} only when the caller did not override it.</li>
     * </ul>
     */
    @Override
    public HttpObject[] write(Request request, HttpVersion version) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        PreparedBody prepared = request.body().prepare();
        DefaultHttpRequest requestLine = new DefaultHttpRequest(version, request.method(), request.requestTarget());
        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
        for (Map.Entry<String, List<String>> entry : request.headerMap().entrySet()) {
            for (String value : entry.getValue()) {
                headers.addHeader(entry.getKey(), value);
            }
        }

        if (!headers.containsHeader(HttpHeaderNames.HOST)) {
            headers.addHeader(HttpHeaderNames.HOST, request.hostHeader());
        }
        if (!headers.containsHeader(HttpHeaderNames.CONTENT_TYPE) && StringUtils.isNotBlank(prepared.defaultContentType())) {
            headers.addHeader(HttpHeaderNames.CONTENT_TYPE, prepared.defaultContentType());
        }
        boolean hasTransferEncoding = headers.containsHeader(HttpHeaderNames.TRANSFER_ENCODING);
        boolean hasContentLength = headers.containsHeader(HttpHeaderNames.CONTENT_LENGTH);
        boolean supportsChunkedTransfer = version.majorVersion() == 1 && version.minorVersion() >= 1;
        if (!hasTransferEncoding && prepared.hasKnownLength() && !hasContentLength) {
            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(prepared.contentLength()));
        }
        if (!hasTransferEncoding && !prepared.hasKnownLength() && !hasContentLength && supportsChunkedTransfer) {
            headers.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        if (version.majorVersion() == 1 && version.isKeepAliveDefault() && !headers.containsHeader(HttpHeaderNames.CONNECTION)) {
            headers.addHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        ArrayList<HttpObject> httpObjects = new ArrayList<>();
        httpObjects.add(requestLine);
        httpObjects.add(headers);

        List<ByteBuf> parts = prepared.parts();
        if (parts.isEmpty()) {
            httpObjects.add(new DefaultLastHttpContent(ByteBuf.EMPTY));
        } else {
            for (int i = 0; i < parts.size(); i++) {
                ByteBuf part = parts.get(i);
                if (i + 1 == parts.size()) {
                    httpObjects.add(new DefaultLastHttpContent(part == null ? ByteBuf.EMPTY : part));
                } else {
                    httpObjects.add(new DefaultHttpContent(part == null ? ByteBuf.EMPTY : part));
                }
            }
        }
        return httpObjects.toArray(new HttpObject[0]);
    }
}
