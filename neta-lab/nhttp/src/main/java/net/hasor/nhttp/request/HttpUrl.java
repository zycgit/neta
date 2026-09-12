/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.HttpScheme;

/**
 * Immutable URL wrapper with a builder dedicated to HTTP request construction.
 * <p>
 * The builder percent-encodes path and query components in an RFC 3986-oriented way and also
 * normalizes host literals so later request generation can emit both the request-target and the
 * {@code Host} authority consistently.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class HttpUrl {
    private final URI uri;

    private HttpUrl(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        this.uri = uri;
    }

    /** Creates a structured URL from a raw URL string. */
    public static HttpUrl of(String url) {
        return new HttpUrl(URI.create(url));
    }

    /** Creates a structured URL from an existing {@link URI}. */
    public static HttpUrl of(URI uri) {
        return new HttpUrl(uri);
    }

    /** Starts a builder from a raw URL string. */
    public static Builder builder(String url) {
        return new Builder(URI.create(url));
    }

    /** Starts a builder from an existing {@link URI}. */
    public static Builder builder(URI uri) {
        return new Builder(uri);
    }

    /** Starts an empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the final {@link URI} used by {@link Request}. */
    public URI toUri() {
        return this.uri;
    }

    @Override
    public String toString() {
        return this.uri.toString();
    }

    public static final class Builder {
        private String                scheme;
        private String                userInfo;
        private String                host;
        private int                   port            = -1;
        private final List<String>    pathSegments    = new ArrayList<>();
        private final List<NameValue> queryParameters = new ArrayList<>();
        private String                fragment;

        private Builder() {
        }

        private Builder(URI uri) {
            if (uri == null) {
                throw new IllegalArgumentException("uri must not be null");
            }
            this.scheme = uri.getScheme();
            this.userInfo = uri.getRawUserInfo();
            this.host = uri.getHost();
            this.port = uri.getPort();
            this.fragment = uri.getRawFragment();
            this.initRawPath(uri.getRawPath());
            this.initQuery(uri.getRawQuery());
        }

        /** Sets the URI scheme. */
        public Builder scheme(HttpScheme scheme) {
            if (scheme == null) {
                throw new IllegalArgumentException("scheme must not be null");
            }
            this.scheme = scheme.name();
            return this;
        }

        /** Sets the URI scheme from a raw token such as {@code http} or {@code https}. */
        public Builder scheme(String scheme) {
            if (StringUtils.isBlank(scheme)) {
                throw new IllegalArgumentException("scheme must not be blank");
            }
            this.scheme = scheme;
            return this;
        }

        /** Sets and percent-encodes the user-info component. */
        public Builder userInfo(String userInfo) {
            this.userInfo = encodeComponent(userInfo, false);
            return this;
        }

        /**
         * Sets the host component.
         * <p>
         * IPv6 literals are automatically wrapped in square brackets so the resulting URI authority
         * follows RFC 3986 Section 3.2.2 syntax and later produces a correct HTTP host authority.
         */
        public Builder host(String host) {
            if (StringUtils.isBlank(host)) {
                throw new IllegalArgumentException("host must not be blank");
            }
            this.host = normalizeHost(host);
            return this;
        }

        /** Sets the explicit authority port, or {@code -1} to omit it. */
        public Builder port(int port) {
            if (port < -1 || port > 65535) {
                throw new IllegalArgumentException("port must be -1 or between 0 and 65535");
            }
            this.port = port;
            return this;
        }

        /** Replaces the entire path, percent-encoding each plain segment. */
        public Builder path(String path) {
            this.pathSegments.clear();
            this.initPlainPath(path);
            return this;
        }

        /** Adds one plain path segment and percent-encodes reserved characters for RFC 3986 use. */
        public Builder addPathSegment(String pathSegment) {
            if (pathSegment == null) {
                throw new IllegalArgumentException("pathSegment must not be null");
            }
            this.pathSegments.add(encodeComponent(pathSegment, true));
            return this;
        }

        /** Adds one already-encoded path segment without re-encoding it. */
        public Builder addEncodedPathSegment(String encodedPathSegment) {
            if (encodedPathSegment == null) {
                throw new IllegalArgumentException("encodedPathSegment must not be null");
            }
            this.pathSegments.add(stripSlashes(encodedPathSegment));
            return this;
        }

        /** Removes all configured path segments so the built URL falls back to {@code /}. */
        public Builder clearPath() {
            this.pathSegments.clear();
            return this;
        }

        /**
         * Adds one query parameter after UTF-8 percent-encoding the name and value for URI use.
         */
        public Builder addQueryParameter(String name, String value) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("query parameter name must not be blank");
            }
            this.queryParameters.add(new NameValue(urlEncode(name), urlEncode(value == null ? "" : value)));
            return this;
        }

        /** Adds one query parameter whose name and value are already encoded. */
        public Builder addEncodedQueryParameter(String encodedName, String encodedValue) {
            if (StringUtils.isBlank(encodedName)) {
                throw new IllegalArgumentException("encodedName must not be blank");
            }
            this.queryParameters.add(new NameValue(encodedName, encodedValue == null ? "" : encodedValue));
            return this;
        }

        /** Clears the entire query string. */
        public Builder clearQuery() {
            this.queryParameters.clear();
            return this;
        }

        /** Sets and percent-encodes the fragment component. */
        public Builder fragment(String fragment) {
            this.fragment = encodeComponent(fragment, false);
            return this;
        }

        /** Validates the minimum URL components and builds the immutable URL object. */
        public HttpUrl build() {
            if (StringUtils.isBlank(this.scheme)) {
                throw new IllegalStateException("scheme must not be blank");
            }
            if (StringUtils.isBlank(this.host)) {
                throw new IllegalStateException("host must not be blank");
            }

            StringBuilder builder = new StringBuilder();
            builder.append(this.scheme).append("://");
            if (StringUtils.isNotBlank(this.userInfo)) {
                builder.append(this.userInfo).append('@');
            }
            builder.append(this.host);
            if (this.port >= 0) {
                builder.append(':').append(this.port);
            }

            builder.append(this.buildPath());

            String query = this.buildQuery();
            if (StringUtils.isNotBlank(query)) {
                builder.append('?').append(query);
            }
            if (StringUtils.isNotBlank(this.fragment)) {
                builder.append('#').append(this.fragment);
            }
            return new HttpUrl(URI.create(builder.toString()));
        }

        private void initRawPath(String rawPath) {
            if (StringUtils.isBlank(rawPath) || "/".equals(rawPath)) {
                return;
            }
            String path = rawPath;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                return;
            }
            String[] segments = path.split("/");
            Collections.addAll(this.pathSegments, segments);
        }

        private void initPlainPath(String pathValue) {
            if (StringUtils.isBlank(pathValue) || "/".equals(pathValue)) {
                return;
            }
            String path = pathValue;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                return;
            }
            String[] segments = path.split("/");
            for (String segment : segments) {
                this.pathSegments.add(encodeComponent(segment, true));
            }
        }

        private void initQuery(String rawQuery) {
            if (StringUtils.isBlank(rawQuery)) {
                return;
            }
            String[] segments = rawQuery.split("&");
            for (String segment : segments) {
                int split = segment.indexOf('=');
                if (split < 0) {
                    this.queryParameters.add(new NameValue(segment, ""));
                } else {
                    this.queryParameters.add(new NameValue(segment.substring(0, split), segment.substring(split + 1)));
                }
            }
        }

        private String buildPath() {
            if (this.pathSegments.isEmpty()) {
                return "/";
            }
            StringBuilder builder = new StringBuilder();
            for (String pathSegment : this.pathSegments) {
                builder.append('/').append(pathSegment);
            }
            return builder.toString();
        }

        private String buildQuery() {
            if (this.queryParameters.isEmpty()) {
                return null;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < this.queryParameters.size(); i++) {
                NameValue parameter = this.queryParameters.get(i);
                if (i > 0) {
                    builder.append('&');
                }
                builder.append(parameter.name).append('=').append(parameter.value);
            }
            return builder.toString();
        }

        private static String stripSlashes(String value) {
            String result = value;
            while (result.startsWith("/")) {
                result = result.substring(1);
            }
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }

        private static String normalizeHost(String host) {
            String normalized = host.trim();
            if (normalized.indexOf(':') >= 0 && !(normalized.startsWith("[") && normalized.endsWith("]"))) {
                return '[' + normalized + ']';
            }
            return normalized;
        }

        private static String encodeComponent(String value, boolean preserveDot) {
            if (value == null) {
                return null;
            }
            String encoded = urlEncode(value);
            return preserveDot ? encoded.replace("%2E", ".") : encoded;
        }

        private static String urlEncode(String value) {
            try {
                return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static final class NameValue {
            private final String name;
            private final String value;

            private NameValue(String name, String value) {
                this.name = name;
                this.value = value;
            }
        }
    }
}
