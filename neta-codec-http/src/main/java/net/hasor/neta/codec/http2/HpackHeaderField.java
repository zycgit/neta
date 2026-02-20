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
package net.hasor.neta.codec.http2;

/**
 * Represents a header field entry in the HPACK dynamic/static table.
 * <p>
 * Per RFC 7541, Section 2.3.2:
 * <pre>
 *   The size of an entry is the sum of its name's length in octets,
 *   its value's length in octets, and 32.
 * </pre>
 */
public final class HpackHeaderField {
    /** Overhead per entry in the HPACK dynamic table (RFC 7541, Section 4.1). */
    static final int ENTRY_OVERHEAD = 32;

    final String name;
    final String value;

    public HpackHeaderField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    /** Returns the size of this entry as defined by RFC 7541, Section 4.1. */
    public int size() {
        return name.length() + value.length() + ENTRY_OVERHEAD;
    }

    public String name() {
        return name;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
