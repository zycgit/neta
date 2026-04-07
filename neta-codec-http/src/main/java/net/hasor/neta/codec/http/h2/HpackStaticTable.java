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
import java.util.HashMap;
import java.util.Map;
import net.hasor.neta.codec.http.HttpHeaderNames;

/**
 * HPACK static table defined by RFC 7541 Appendix A.
 * <p>
 * The static table consists of 61 predefined header field entries that are always available to the decoder.
 * <p>
 * This implementation uses HashMap-based lookups to optimize name and name+value matches from
 * O(61) linear scans to O(1) lookups.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
final class HpackStaticTable {
    /** Maps a header name to its first matching index, using 1-based indexing. */
    private static final Map<String, Integer> NAME_INDEX_MAP;
    /** Maps a name+value composite key to its exact-match index, using 1-based indexing. */
    private static final Map<Long, Integer>   NAME_VALUE_INDEX_MAP;

    /** Static table entries using 1-based indexing; slot 0 is unused. */
    private static final HpackHeaderField[] STATIC_TABLE = {
            /* 0  */ null, // Placeholder; HPACK uses 1-based indexing.
            /* 1  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_AUTHORITY, ""),
            /* 2  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_METHOD, "GET"),
            /* 3  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_METHOD, "POST"),
            /* 4  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_PATH, "/"),
            /* 5  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_PATH, "/index.html"),
            /* 6  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_SCHEME, "http"),
            /* 7  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_SCHEME, "https"),
            /* 8  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "200"),
            /* 9  */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "204"),
            /* 10 */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "206"),
            /* 11 */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "304"),
            /* 12 */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "400"),
            /* 13 */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "404"),
            /* 14 */ new HpackHeaderField(HttpHeaderNames.PSEUDO_STATUS, "500"),
            /* 15 */ new HpackHeaderField(HttpHeaderNames.ACCEPT_CHARSET, ""),
            /* 16 */ new HpackHeaderField(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate"),
            /* 17 */ new HpackHeaderField(HttpHeaderNames.ACCEPT_LANGUAGE, ""),
            /* 18 */ new HpackHeaderField(HttpHeaderNames.ACCEPT_RANGES, ""),
            /* 19 */ new HpackHeaderField(HttpHeaderNames.ACCEPT, ""),
            /* 20 */ new HpackHeaderField(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, ""),
            /* 21 */ new HpackHeaderField(HttpHeaderNames.AGE, ""),
            /* 22 */ new HpackHeaderField(HttpHeaderNames.ALLOW, ""),
            /* 23 */ new HpackHeaderField(HttpHeaderNames.AUTHORIZATION, ""),
            /* 24 */ new HpackHeaderField(HttpHeaderNames.CACHE_CONTROL, ""),
            /* 25 */ new HpackHeaderField(HttpHeaderNames.CONTENT_DISPOSITION, ""),
            /* 26 */ new HpackHeaderField(HttpHeaderNames.CONTENT_ENCODING, ""),
            /* 27 */ new HpackHeaderField(HttpHeaderNames.CONTENT_LANGUAGE, ""),
            /* 28 */ new HpackHeaderField(HttpHeaderNames.CONTENT_LENGTH, ""),
            /* 29 */ new HpackHeaderField(HttpHeaderNames.CONTENT_LOCATION, ""),
            /* 30 */ new HpackHeaderField(HttpHeaderNames.CONTENT_RANGE, ""),
            /* 31 */ new HpackHeaderField(HttpHeaderNames.CONTENT_TYPE, ""),
            /* 32 */ new HpackHeaderField(HttpHeaderNames.COOKIE, ""),
            /* 33 */ new HpackHeaderField(HttpHeaderNames.DATE, ""),
            /* 34 */ new HpackHeaderField(HttpHeaderNames.ETAG, ""),
            /* 35 */ new HpackHeaderField(HttpHeaderNames.EXPECT, ""),
            /* 36 */ new HpackHeaderField(HttpHeaderNames.EXPIRES, ""),
            /* 37 */ new HpackHeaderField(HttpHeaderNames.FROM, ""),
            /* 38 */ new HpackHeaderField(HttpHeaderNames.HOST, ""),
            /* 39 */ new HpackHeaderField(HttpHeaderNames.IF_MATCH, ""),
            /* 40 */ new HpackHeaderField(HttpHeaderNames.IF_MODIFIED_SINCE, ""),
            /* 41 */ new HpackHeaderField(HttpHeaderNames.IF_NONE_MATCH, ""),
            /* 42 */ new HpackHeaderField(HttpHeaderNames.IF_RANGE, ""),
            /* 43 */ new HpackHeaderField(HttpHeaderNames.IF_UNMODIFIED_SINCE, ""),
            /* 44 */ new HpackHeaderField(HttpHeaderNames.LAST_MODIFIED, ""),
            /* 45 */ new HpackHeaderField(HttpHeaderNames.LINK, ""),
            /* 46 */ new HpackHeaderField(HttpHeaderNames.LOCATION, ""),
            /* 47 */ new HpackHeaderField(HttpHeaderNames.MAX_FORWARDS, ""),
            /* 48 */ new HpackHeaderField(HttpHeaderNames.PROXY_AUTHENTICATE, ""),
            /* 49 */ new HpackHeaderField(HttpHeaderNames.PROXY_AUTHORIZATION, ""),
            /* 50 */ new HpackHeaderField(HttpHeaderNames.RANGE, ""),
            /* 51 */ new HpackHeaderField(HttpHeaderNames.REFERER, ""),
            /* 52 */ new HpackHeaderField(HttpHeaderNames.REFRESH, ""),
            /* 53 */ new HpackHeaderField(HttpHeaderNames.RETRY_AFTER, ""),
            /* 54 */ new HpackHeaderField(HttpHeaderNames.SERVER, ""),
            /* 55 */ new HpackHeaderField(HttpHeaderNames.SET_COOKIE, ""),
            /* 56 */ new HpackHeaderField(HttpHeaderNames.STRICT_TRANSPORT_SECURITY, ""),
            /* 57 */ new HpackHeaderField(HttpHeaderNames.TRANSFER_ENCODING, ""),
            /* 58 */ new HpackHeaderField(HttpHeaderNames.USER_AGENT, ""),
            /* 59 */ new HpackHeaderField(HttpHeaderNames.VARY, ""),
            /* 60 */ new HpackHeaderField(HttpHeaderNames.VIA, ""),
            /* 61 */ new HpackHeaderField(HttpHeaderNames.WWW_AUTHENTICATE, ""), };

    /** Number of static table entries, covering indices 1..61. */
    public static final int LENGTH = STATIC_TABLE.length - 1;

    static {
        // Build HashMap indexes for O(1) lookups.
        NAME_INDEX_MAP = new HashMap<>(64);
        NAME_VALUE_INDEX_MAP = new HashMap<>(64);
        for (int i = 1; i < STATIC_TABLE.length; i++) {
            HpackHeaderField f = STATIC_TABLE[i];
            NAME_INDEX_MAP.putIfAbsent(f.name(), i);
            NAME_VALUE_INDEX_MAP.putIfAbsent(nameValueKey(f.name(), f.value()), i);
        }
    }

    private HpackStaticTable() {
    }

    private static long nameValueKey(String name, String value) {
        // Combine the name and value hashes into a long key to reduce collision probability with 64 bits.
        return ((long) name.hashCode() << 32) | (value.hashCode() & 0xFFFFFFFFL);
    }

    /**
     * Returns the static table entry for the specified 1-based index.
     * @param index the 1-based index, in the range 1..61
     * @return the header field entry
     * @throws HpackDecodingException if the index is out of range
     */
    public static HpackHeaderField get(int index) {
        if (index < 1 || index >= STATIC_TABLE.length) {
            throw new HpackDecodingException("HPACK: invalid static table index " + index);
        }
        return STATIC_TABLE[index];
    }

    /**
     * Finds the index of a header name in the static table.
     * Returns the first matching 1-based index, or -1 when there is no match.
     * This lookup is implemented with a HashMap and runs in O(1).
     * @param name the header name
     * @return the matching index, or -1 when not found
     */
    public static int findName(String name) {
        Integer idx = NAME_INDEX_MAP.get(name);
        return idx != null ? idx : -1;
    }

    /**
     * Finds the index of a header name+value pair in the static table.
     * A 1-based index is returned only when both the name and value match; otherwise -1 is returned.
     * The normal path is O(1) via HashMap lookup, with linear scan fallback in the rare case of hash collisions.
     * @param name the header name
     * @param value the header value
     * @return the matching index, or -1 when not found
     */
    public static int findNameValue(String name, String value) {
        long key = nameValueKey(name, value);
        Integer idx = NAME_VALUE_INDEX_MAP.get(key);
        if (idx != null) {
            HpackHeaderField f = STATIC_TABLE[idx];
            if (f.name().equals(name) && f.value().equals(value)) {
                return idx;
            }
            // Fall back to a linear scan on hash collision; this path should be extremely rare.
            for (int i = 1; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i].name().equals(name) && STATIC_TABLE[i].value().equals(value)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
