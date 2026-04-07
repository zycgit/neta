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
