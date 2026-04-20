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
 * Represents an HTTP request method, as defined in
 * <a href="https://tools.ietf.org/html/rfc7231#section-4">RFC 7231, Section 4</a>
 * and <a href="https://tools.ietf.org/html/rfc5789">RFC 5789 (PATCH)</a>.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class HttpMethod {
    /** OPTIONS method. See RFC 7231 §4.3.7. */
    public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");

    /** GET method. See RFC 7231 §4.3.1. */
    public static final HttpMethod GET = new HttpMethod("GET");

    /** HEAD method. See RFC 7231 §4.3.2. */
    public static final HttpMethod HEAD = new HttpMethod("HEAD");

    /** POST method. See RFC 7231 §4.3.3. */
    public static final HttpMethod POST = new HttpMethod("POST");

    /** PUT method. See RFC 7231 §4.3.4. */
    public static final HttpMethod PUT = new HttpMethod("PUT");

    /** PATCH method. See RFC 5789. */
    public static final HttpMethod PATCH = new HttpMethod("PATCH");

    /** DELETE method. See RFC 7231 §4.3.5. */
    public static final HttpMethod DELETE = new HttpMethod("DELETE");

    /** TRACE method. See RFC 7231 §4.3.8. */
    public static final HttpMethod TRACE = new HttpMethod("TRACE");

    /** CONNECT method. See RFC 7231 §4.3.6. */
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
     * Creates an HTTP method with the given name.
     * The method name is validated as an HTTP token according to RFC 7230 §3.2.6.
     * @param name method name, conventionally case-sensitive and usually uppercase
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
     * Returns the {@link HttpMethod} for the given method name.
     * Returns a cached constant for standard HTTP methods; otherwise creates a new instance.
     * @param name method name
     * @return matching HttpMethod
     */
    public static HttpMethod valueOf(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        // Fast path: direct lookup for canonical uppercase method names without extra whitespace.
        HttpMethod known = KNOWN_METHODS.get(name);
        if (known != null) {
            return known;
        }
        // Slow path: normalize first, then try again.
        name = name.trim().toUpperCase();
        known = KNOWN_METHODS.get(name);
        return known != null ? known : new HttpMethod(name);
    }

    /**
     * Returns the {@link HttpMethod} for the given method text.
     * @param name method text
     * @return matching HttpMethod
     */
    public static HttpMethod valueOf(CharSequence name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (name instanceof String) {
            return valueOf((String) name);
        }
        HttpMethod known = knownMethod(name);
        return known != null ? known : new HttpMethod(name.toString().trim().toUpperCase());
    }

    private static HttpMethod knownMethod(CharSequence name) {
        if (matches(name, OPTIONS.name)) {
            return OPTIONS;
        }
        if (matches(name, GET.name)) {
            return GET;
        }
        if (matches(name, HEAD.name)) {
            return HEAD;
        }
        if (matches(name, POST.name)) {
            return POST;
        }
        if (matches(name, PUT.name)) {
            return PUT;
        }
        if (matches(name, PATCH.name)) {
            return PATCH;
        }
        if (matches(name, DELETE.name)) {
            return DELETE;
        }
        if (matches(name, TRACE.name)) {
            return TRACE;
        }
        if (matches(name, CONNECT.name)) {
            return CONNECT;
        }
        return null;
    }

    private static boolean matches(CharSequence left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < right.length(); i++) {
            char c1 = left.charAt(i);
            char c2 = right.charAt(i);
            if (c1 == c2) {
                continue;
            }
            if (c1 >= 'a' && c1 <= 'z') {
                c1 = (char) (c1 - 32);
            }
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the method name, such as "GET" or "POST".
     * @return method name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the cached ASCII bytes of the method name.
     * @return method name bytes
     */
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
