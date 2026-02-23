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
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an HTTP request method as defined in
 * <a href="https://tools.ietf.org/html/rfc7231#section-4">RFC 7231, Section 4</a>
 * and <a href="https://tools.ietf.org/html/rfc5789">RFC 5789 (PATCH)</a>.
 */
public final class HttpMethod {
    /** The OPTIONS method (RFC 7231 §4.3.7). */
    public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");

    /** The GET method (RFC 7231 §4.3.1). */
    public static final HttpMethod GET = new HttpMethod("GET");

    /** The HEAD method (RFC 7231 §4.3.2). */
    public static final HttpMethod HEAD = new HttpMethod("HEAD");

    /** The POST method (RFC 7231 §4.3.3). */
    public static final HttpMethod POST = new HttpMethod("POST");

    /** The PUT method (RFC 7231 §4.3.4). */
    public static final HttpMethod PUT = new HttpMethod("PUT");

    /** The PATCH method (RFC 5789). */
    public static final HttpMethod PATCH = new HttpMethod("PATCH");

    /** The DELETE method (RFC 7231 §4.3.5). */
    public static final HttpMethod DELETE = new HttpMethod("DELETE");

    /** The TRACE method (RFC 7231 §4.3.8). */
    public static final HttpMethod TRACE = new HttpMethod("TRACE");

    /** The CONNECT method (RFC 7231 §4.3.6). */
    public static final HttpMethod CONNECT = new HttpMethod("CONNECT");

    private static final Map<String, HttpMethod> KNOWN_METHODS = new HashMap<>();

    static {
        KNOWN_METHODS.put(OPTIONS.name, OPTIONS);
        KNOWN_METHODS.put(GET.name, GET);
        KNOWN_METHODS.put(HEAD.name, HEAD);
        KNOWN_METHODS.put(POST.name, POST);
        KNOWN_METHODS.put(PUT.name, PUT);
        KNOWN_METHODS.put(PATCH.name, PATCH);
        KNOWN_METHODS.put(DELETE.name, DELETE);
        KNOWN_METHODS.put(TRACE.name, TRACE);
        KNOWN_METHODS.put(CONNECT.name, CONNECT);
    }

    private final String name;
    private final byte[] nameBytes;

    /**
     * Creates a new HTTP method with the specified name.
     * The name is validated as an HTTP token per RFC 7230 §3.2.6.
     * @param name the method name (case-sensitive, uppercase by convention)
     * @throws IllegalArgumentException if the name is null, empty, or contains invalid characters
     */
    public HttpMethod(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        name = name.trim();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("invalid character in HTTP method name: 0x" + Integer.toHexString(c));
            }
        }
        this.name = name;
        this.nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Returns the {@link HttpMethod} instance for the given method name.
     * If the name matches a standard HTTP method, the cached constant is returned.
     * Otherwise, a new instance is created.
     * @param name the method name
     * @return the corresponding HttpMethod
     */
    public static HttpMethod valueOf(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        // Fast path: try direct lookup first (works for canonical uppercase methods without whitespace)
        HttpMethod known = KNOWN_METHODS.get(name);
        if (known != null) {
            return known;
        }
        // Slow path: normalize and retry
        name = name.trim().toUpperCase();
        known = KNOWN_METHODS.get(name);
        return known != null ? known : new HttpMethod(name);
    }

    /** Returns the method name (e.g., "GET", "POST"). */
    public String name() {
        return name;
    }

    /** Returns the pre-cached ASCII bytes of the method name. */
    public byte[] nameBytes() {
        return nameBytes;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpMethod)) {
            return false;
        }
        return name.equals(((HttpMethod) o).name);
    }
}
