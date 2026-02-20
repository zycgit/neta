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
 * HPACK static table as defined in RFC 7541, Appendix A.
 * <p>
 * The static table consists of 61 pre-defined header field entries
 * that are always available to the decoder.
 */
public final class HpackStaticTable {
    /** The static table entries (1-indexed; entry 0 is unused). */
    private static final HpackHeaderField[] STATIC_TABLE = {
            /* 0  */ null, // placeholder (HPACK is 1-indexed)
            /* 1  */ new HpackHeaderField(":authority", ""),
            /* 2  */ new HpackHeaderField(":method", "GET"),
            /* 3  */ new HpackHeaderField(":method", "POST"),
            /* 4  */ new HpackHeaderField(":path", "/"),
            /* 5  */ new HpackHeaderField(":path", "/index.html"),
            /* 6  */ new HpackHeaderField(":scheme", "http"),
            /* 7  */ new HpackHeaderField(":scheme", "https"),
            /* 8  */ new HpackHeaderField(":status", "200"),
            /* 9  */ new HpackHeaderField(":status", "204"),
            /* 10 */ new HpackHeaderField(":status", "206"),
            /* 11 */ new HpackHeaderField(":status", "304"),
            /* 12 */ new HpackHeaderField(":status", "400"),
            /* 13 */ new HpackHeaderField(":status", "404"),
            /* 14 */ new HpackHeaderField(":status", "500"),
            /* 15 */ new HpackHeaderField("accept-charset", ""),
            /* 16 */ new HpackHeaderField("accept-encoding", "gzip, deflate"),
            /* 17 */ new HpackHeaderField("accept-language", ""),
            /* 18 */ new HpackHeaderField("accept-ranges", ""),
            /* 19 */ new HpackHeaderField("accept", ""),
            /* 20 */ new HpackHeaderField("access-control-allow-origin", ""),
            /* 21 */ new HpackHeaderField("age", ""),
            /* 22 */ new HpackHeaderField("allow", ""),
            /* 23 */ new HpackHeaderField("authorization", ""),
            /* 24 */ new HpackHeaderField("cache-control", ""),
            /* 25 */ new HpackHeaderField("content-disposition", ""),
            /* 26 */ new HpackHeaderField("content-encoding", ""),
            /* 27 */ new HpackHeaderField("content-language", ""),
            /* 28 */ new HpackHeaderField("content-length", ""),
            /* 29 */ new HpackHeaderField("content-location", ""),
            /* 30 */ new HpackHeaderField("content-range", ""),
            /* 31 */ new HpackHeaderField("content-type", ""),
            /* 32 */ new HpackHeaderField("cookie", ""),
            /* 33 */ new HpackHeaderField("date", ""),
            /* 34 */ new HpackHeaderField("etag", ""),
            /* 35 */ new HpackHeaderField("expect", ""),
            /* 36 */ new HpackHeaderField("expires", ""),
            /* 37 */ new HpackHeaderField("from", ""),
            /* 38 */ new HpackHeaderField("host", ""),
            /* 39 */ new HpackHeaderField("if-match", ""),
            /* 40 */ new HpackHeaderField("if-modified-since", ""),
            /* 41 */ new HpackHeaderField("if-none-match", ""),
            /* 42 */ new HpackHeaderField("if-range", ""),
            /* 43 */ new HpackHeaderField("if-unmodified-since", ""),
            /* 44 */ new HpackHeaderField("last-modified", ""),
            /* 45 */ new HpackHeaderField("link", ""),
            /* 46 */ new HpackHeaderField("location", ""),
            /* 47 */ new HpackHeaderField("max-forwards", ""),
            /* 48 */ new HpackHeaderField("proxy-authenticate", ""),
            /* 49 */ new HpackHeaderField("proxy-authorization", ""),
            /* 50 */ new HpackHeaderField("range", ""),
            /* 51 */ new HpackHeaderField("referer", ""),
            /* 52 */ new HpackHeaderField("refresh", ""),
            /* 53 */ new HpackHeaderField("retry-after", ""),
            /* 54 */ new HpackHeaderField("server", ""),
            /* 55 */ new HpackHeaderField("set-cookie", ""),
            /* 56 */ new HpackHeaderField("strict-transport-security", ""),
            /* 57 */ new HpackHeaderField("transfer-encoding", ""),
            /* 58 */ new HpackHeaderField("user-agent", ""),
            /* 59 */ new HpackHeaderField("vary", ""),
            /* 60 */ new HpackHeaderField("via", ""),
            /* 61 */ new HpackHeaderField("www-authenticate", ""), };

    /** Number of entries in the static table (1..61). */
    public static final int LENGTH = STATIC_TABLE.length - 1;

    private HpackStaticTable() {
    }

    /**
     * Returns the static table entry at the given 1-based index.
     * @param index the 1-based index (1..61)
     * @return the header field entry
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static HpackHeaderField get(int index) {
        if (index < 1 || index >= STATIC_TABLE.length) {
            throw new IndexOutOfBoundsException("invalid HPACK static table index: " + index);
        }
        return STATIC_TABLE[index];
    }

    /**
     * Finds the index of a header name in the static table.
     * Returns the first matching index (1-based), or -1 if not found.
     */
    public static int findName(String name) {
        for (int i = 1; i < STATIC_TABLE.length; i++) {
            if (STATIC_TABLE[i].name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the index of a header name+value pair in the static table.
     * Returns the 1-based index if both name and value match, or -1.
     */
    public static int findNameValue(String name, String value) {
        for (int i = 1; i < STATIC_TABLE.length; i++) {
            if (STATIC_TABLE[i].name.equals(name) && STATIC_TABLE[i].value.equals(value)) {
                return i;
            }
        }
        return -1;
    }
}
