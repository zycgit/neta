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
import java.util.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;

/**
 * HTTP headers container with case-insensitive header name matching.
 * <p>
 * Per <a href="https://tools.ietf.org/html/rfc7230#section-3.2">RFC 7230, Section 3.2</a>,
 * header field names are case-insensitive. Multiple values for the same header name are supported.
 * <p>
 * Header format (RFC 7230 §3.2):
 * <pre>
 *   header-field = field-name ":" OWS field-value OWS
 *   field-name   = token
 *   field-value  = *( field-content / obs-fold )
 * </pre>
 */
public class HttpHeaders extends HttpHeaderNames implements Iterable<Map.Entry<String, String>> {
    /** An empty, unmodifiable {@link HttpHeaders} instance. */
    public static final HttpHeaders EMPTY = new HttpHeaders(Collections.emptyMap(), true);

    // Use a LinkedHashMap to preserve insertion order, with lowercase keys
    private final Map<String, List<String>> headers;
    private final boolean                   readOnly;

    /** Creates a new, empty {@link HttpHeaders} instance. */
    public HttpHeaders() {
        this.headers = new LinkedHashMap<>();
        this.readOnly = false;
    }

    private HttpHeaders(Map<String, List<String>> headers, boolean readOnly) {
        this.headers = headers;
        this.readOnly = readOnly;
    }

    /**
     * Case-insensitive substring check without creating a temporary lowercase copy.
     * @param source the source string to search in
     * @param target the target substring to search for (must be lowercase)
     * @return true if source contains target (case-insensitive)
     */
    static boolean containsIgnoreCase(String source, String target) {
        int targetLen = target.length();
        int sourceLen = source.length();
        int maxStart = sourceLen - targetLen;
        for (int i = 0; i <= maxStart; i++) {
            boolean found = true;
            for (int j = 0; j < targetLen; j++) {
                if (Character.toLowerCase(source.charAt(i + j)) != target.charAt(j)) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }

    private void checkReadOnly() {
        if (readOnly) {
            throw new UnsupportedOperationException("read-only HttpHeaders");
        }
    }

    /**
     * Adds a header with the specified name and value.
     * Multiple values for the same name are supported.
     * @param name the header name
     * @param value the header value
     * @return this headers instance for chaining
     */
    public HttpHeaders add(String name, String value) {
        checkReadOnly();
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        List<String> values = headers.get(lowerName);
        if (values == null) {
            values = new ArrayList<String>(1);
            headers.put(lowerName, values);
        }
        values.add(value);
        return this;
    }

    /**
     * Sets a header with the specified name and value, replacing any existing values.
     * @param name the header name
     * @param value the header value
     * @return this headers instance for chaining
     */
    public HttpHeaders set(String name, String value) {
        checkReadOnly();
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<String>(1);
        values.add(value);
        headers.put(lowerName, values);
        return this;
    }

    /**
     * Sets a header with the specified name and multiple values, replacing any existing values.
     * @param name the header name
     * @param values the header values
     * @return this headers instance for chaining
     */
    public HttpHeaders set(String name, List<String> values) {
        checkReadOnly();
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be null or empty");
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        headers.put(lowerName, new ArrayList<String>(values));
        return this;
    }

    /**
     * Returns the first value of the header with the specified name,
     * or {@code null} if no such header exists.
     * @param name the header name (case-insensitive)
     * @return the first header value, or null
     */
    public String get(String name) {
        if (name == null) {
            return null;
        }
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /**
     * Returns all values of the header with the specified name,
     * or an empty list if no such header exists.
     * @param name the header name (case-insensitive)
     * @return a list of header values (never null)
     */
    public List<String> getAll(String name) {
        if (name == null) {
            return Collections.emptyList();
        }
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values != null ? Collections.unmodifiableList(values) : Collections.<String>emptyList();
    }

    /**
     * Returns the value of the header as an integer, or the default value
     * if the header is not present or cannot be parsed.
     * @param name the header name
     * @param defaultValue the default value
     * @return the integer value or defaultValue
     */
    public int getInt(String name, int defaultValue) {
        String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns the value of the header as a long, or the default value
     * if the header is not present or cannot be parsed.
     * @param name the header name
     * @param defaultValue the default value
     * @return the long value or defaultValue
     */
    public long getLong(String name, long defaultValue) {
        String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns {@code true} if a header with the specified name exists.
     * @param name the header name (case-insensitive)
     */
    public boolean contains(String name) {
        if (name == null) {
            return false;
        }
        return headers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns {@code true} if a header with the specified name and value exists.
     * @param name the header name (case-insensitive)
     * @param value the expected value
     * @param ignoreCase whether to compare values case-insensitively
     */
    public boolean contains(String name, String value, boolean ignoreCase) {
        if (name == null || value == null) {
            return false;
        }
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (ignoreCase ? value.equalsIgnoreCase(v) : value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes all headers with the specified name.
     * @param name the header name (case-insensitive)
     * @return this headers instance for chaining
     */
    public HttpHeaders remove(String name) {
        checkReadOnly();
        if (name != null) {
            headers.remove(name.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Removes all headers. */
    public HttpHeaders clear() {
        checkReadOnly();
        headers.clear();
        return this;
    }

    /** Returns the set of all header names (in lowercase). */
    public Set<String> names() {
        return Collections.unmodifiableSet(headers.keySet());
    }

    /** Returns {@code true} if there are no headers. */
    public boolean isEmpty() {
        return headers.isEmpty();
    }

    /** Returns the total number of header name-value pairs. */
    public int size() {
        int count = 0;
        for (List<String> values : headers.values()) {
            count += values.size();
        }
        return count;
    }

    /**
     * Returns all header entries as a flat list of name-value pairs.
     * Header names are returned in their lowercase form.
     */
    public List<Map.Entry<String, String>> entries() {
        if (headers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, String>> result = new ArrayList<Map.Entry<String, String>>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            for (String value : entry.getValue()) {
                result.add(new AbstractMap.SimpleImmutableEntry<String, String>(name, value));
            }
        }
        return result;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        if (headers.isEmpty()) {
            return Collections.<Map.Entry<String, String>>emptyList().iterator();
        }
        return new Iterator<Map.Entry<String, String>>() {
            private final Iterator<Map.Entry<String, List<String>>> outer = headers.entrySet().iterator();
            private       String                                    currentName;
            private       Iterator<String>                          inner;

            @Override
            public boolean hasNext() {
                while (inner == null || !inner.hasNext()) {
                    if (!outer.hasNext()) {
                        return false;
                    }
                    Map.Entry<String, List<String>> entry = outer.next();
                    currentName = entry.getKey();
                    inner = entry.getValue().iterator();
                }
                return true;
            }

            @Override
            public Map.Entry<String, String> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new AbstractMap.SimpleImmutableEntry<String, String>(currentName, inner.next());
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Creates a shallow copy of this headers instance. */
    public HttpHeaders copy() {
        HttpHeaders copy = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
            copy.headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Copies all headers from the specified source into this instance.
     * @param source the source headers to copy from
     * @return this headers instance for chaining
     */
    public HttpHeaders add(HttpHeaders source) {
        checkReadOnly();
        if (source != null && !source.isEmpty()) {
            for (Map.Entry<String, String> entry : source) {
                add(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : this) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpHeaders)) {
            return false;
        }
        return headers.equals(((HttpHeaders) o).headers);
    }

    @Override
    public int hashCode() {
        return headers.hashCode();
    }
}
