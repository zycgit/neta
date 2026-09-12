/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.cookie.CookieEncoder;
import net.hasor.neta.codec.http.cookie.DefaultCookie;

/**
 * Immutable client request model used before {@link HttpWriter} serializes the request into
 * HTTP/1.x objects.
 * <p>
 * The model keeps URI, method, headers, and body separate so request generation can later derive
 * RFC-relevant wire details such as the origin-form request-target, the {@code Host} field, and
 * body framing headers.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class Request {
    private final URI                       uri;
    private final HttpMethod                method;
    private final Map<String, List<String>> headers;
    private final ContentBody               body;

    private Request(Builder builder) {
        this.uri = builder.uri;
        this.method = builder.method == null ? HttpMethod.GET : builder.method;
        this.body = builder.body == null ? ContentBody.empty() : builder.body;
        this.headers = deepUnmodifiable(builder.headers);
    }

    /**
     * Creates a request from a raw URL string and lets the caller finish configuration with the
     * provided builder callback.
     */
    public static Request of(String url, Consumer<Builder> consumer) {
        Builder builder = new Builder().url(url);
        if (consumer != null) {
            consumer.accept(builder);
        }
        return builder.build();
    }

    /**
     * Creates a request from a structured {@link HttpUrl} and lets the caller finish configuration
     * with the provided builder callback.
     */
    public static Request of(HttpUrl httpUrl, Consumer<Builder> consumer) {
        Builder builder = new Builder().url(httpUrl);
        if (consumer != null) {
            consumer.accept(builder);
        }
        return builder.build();
    }

    /** Returns the final URI that will be converted into the outbound request-target. */
    public URI getUri() {
        return this.uri;
    }

    /** Returns the request method to place into the start-line. */
    public HttpMethod method() {
        return this.method;
    }

    /** Returns the logical message body model associated with this request. */
    public ContentBody body() {
        return this.body;
    }

    /** Returns whether a header with the exact configured field-name exists. */
    public boolean containsHeader(String name) {
        return this.headers.containsKey(name);
    }

    /** Returns the first configured value for a header field, or {@code null} when absent. */
    public String header(String name) {
        List<String> values = this.headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Returns all configured values for a header field in insertion order. */
    public List<String> headers(String name) {
        List<String> values = this.headers.get(name);
        return values == null ? Collections.emptyList() : values;
    }

    /** Returns an immutable view of all configured request headers. */
    public Map<String, List<String>> allHeaders() {
        return this.headers;
    }

    Map<String, List<String>> headerMap() {
        return this.headers;
    }

    /**
     * Builds the HTTP/1.x request-target in origin-form, that is {@code absolute-path[?query]}, as
     * described by RFC 9112 Section 3.2.
     */
    String requestTarget() {
        String path = this.uri.getRawPath();
        if (StringUtils.isBlank(path)) {
            path = "/";
        }
        String query = this.uri.getRawQuery();
        return StringUtils.isBlank(query) ? path : path + "?" + query;
    }

    /**
     * Builds the {@code Host} field value in {@code host[:port]} form, omitting the default port so
     * the emitted authority stays aligned with RFC 9110/RFC 9112 host field conventions.
     */
    String hostHeader() {
        String host = this.uri.getHost();
        int port = this.uri.getPort();
        if (port < 0 || port == defaultPort(this.uri.getScheme())) {
            return host;
        }
        return host + ':' + port;
    }

    private static int defaultPort(String scheme) {
        if (StringUtils.equalsIgnoreCase("https", scheme) || StringUtils.equalsIgnoreCase("wss", scheme)) {
            return 443;
        }
        return 80;
    }

    private static Map<String, List<String>> deepUnmodifiable(Map<String, List<String>> headers) {
        Map<String, List<String>> target = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            target.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(target);
    }

    public static final class Builder {
        private URI                             uri;
        private HttpMethod                      method  = HttpMethod.GET;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private ContentBody                     body    = ContentBody.empty();

        /** Sets the target URL from a raw string. */
        public Builder url(String url) {
            this.uri = URI.create(url);
            return this;
        }

        /** Sets the target URL from a structured {@link HttpUrl}. */
        public Builder url(HttpUrl httpUrl) {
            if (httpUrl == null) {
                throw new IllegalArgumentException("url must not be null");
            }
            this.uri = httpUrl.toUri();
            return this;
        }

        /** Sets the target URL directly from a {@link URI}. */
        public Builder url(URI uri) {
            this.uri = uri;
            return this;
        }

        /**
         * Adds one query parameter by delegating to {@link HttpUrl}, which percent-encodes the name
         * and value using UTF-8 in an RFC 3986-compatible URI-building flow.
         */
        public Builder addQueryParameter(String name, String value) {
            if (this.uri == null) {
                throw new IllegalStateException("url must be set before query parameters");
            }
            this.uri = HttpUrl.builder(this.uri).addQueryParameter(name, value).build().toUri();
            return this;
        }

        /** Sets the request method. */
        public Builder method(HttpMethod method) {
            this.method = method == null ? HttpMethod.GET : method;
            return this;
        }

        /** Sets the request method from its textual token. */
        public Builder method(String method) {
            return this.method(HttpMethod.valueOf(method));
        }

        /** Sets both the request method and the associated message body. */
        public Builder method(HttpMethod method, ContentBody body) {
            this.method(method);
            return this.body(body);
        }

        /** Configures a body-less {@code GET} request. */
        public Builder get() {
            this.method = HttpMethod.GET;
            this.body = ContentBody.empty();
            return this;
        }

        /** Configures a {@code POST} request with the provided body. */
        public Builder post(ContentBody body) {
            return this.method(HttpMethod.POST, body);
        }

        /** Configures a {@code PUT} request with the provided body. */
        public Builder put(ContentBody body) {
            return this.method(HttpMethod.PUT, body);
        }

        /** Configures a {@code PATCH} request with the provided body. */
        public Builder patch(ContentBody body) {
            return this.method(HttpMethod.PATCH, body);
        }

        /** Configures a body-less {@code DELETE} request. */
        public Builder delete() {
            this.method = HttpMethod.DELETE;
            this.body = ContentBody.empty();
            return this;
        }

        /** Configures a {@code DELETE} request that carries a message body. */
        public Builder delete(ContentBody body) {
            return this.method(HttpMethod.DELETE, body);
        }

        /** Sets the logical message body model used during later serialization. */
        public Builder body(ContentBody body) {
            this.body = body == null ? ContentBody.empty() : body;
            return this;
        }

        /** Adds one header value without replacing existing values for the same field-name. */
        public Builder addHeader(String name, String value) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            this.headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value == null ? "" : value);
            return this;
        }

        /** Replaces any existing values for the header field with a single new value. */
        public Builder header(String name, String value) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            ArrayList<String> values = new ArrayList<>();
            values.add(value == null ? "" : value);
            this.headers.put(name, values);
            return this;
        }

        /** Sets the {@code Content-Type} field explicitly, overriding any body default. */
        public Builder contentType(String contentType) {
            return this.header(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        /**
         * Appends a cookie-pair to the outbound {@code Cookie} field using the formatting rules from
         * RFC 6265 cookie header generation.
         */
        public Builder cookie(String name, String value) {
            String encodedCookie = CookieEncoder.encode(new DefaultCookie(name, value));
            List<String> values = this.headers.computeIfAbsent(HttpHeaderNames.COOKIE, key -> new ArrayList<>());
            if (values.isEmpty()) {
                values.add(encodedCookie);
            } else {
                values.set(0, values.get(0) + "; " + encodedCookie);
            }
            return this;
        }

        /** Validates the minimum request state and returns the immutable request instance. */
        public Request build() {
            if (this.uri == null) {
                throw new IllegalStateException("url must not be null");
            }
            return new Request(this);
        }
    }
}
