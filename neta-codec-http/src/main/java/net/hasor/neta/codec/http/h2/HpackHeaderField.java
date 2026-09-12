/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
/**
 * Represents a header field entry in the HPACK dynamic table or static table.
 * <p>
 * According to RFC 7541 Section 2.3.2:
 * <pre>
 *   The size of an entry is the sum of the name length in bytes, the value length in bytes, and 32.
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
final class HpackHeaderField {
    private final String name;
    private final String value;

    /**
     * Creates a header field entry.
     * @param name the header name
     * @param value the header value
     */
    public HpackHeaderField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Returns the size of this entry as defined by RFC 7541 Section 4.1.
     */
    public int size() {
        // Fixed per-entry overhead in the HPACK dynamic table, see RFC 7541 Section 4.1.
        return name.length() + value.length() + 32;
    }

    /**
     * Returns the header name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the header value.
     */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
