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
package net.hasor.neta.codec.http3;

import java.util.HashMap;
import java.util.Map;

/**
 * QPACK static table as defined in RFC 9204, Appendix A.
 * <p>
 * The static table contains 99 pre-defined header fields that are commonly
 * used in HTTP/3. This table is identical for all connections and never changes.
 * Unlike HPACK's static table, QPACK's table is 0-indexed.
 * <p>
 * Uses HashMap-based lookup for O(1) name and name+value matching
 * instead of O(99) linear scans.
 */
public final class QpackStaticTable {
    /** Map from header name to first matching index (0-based). */
    private static final Map<String, Integer> NAME_INDEX_MAP;
    /** Map from name+value key to exact matching index (0-based). */
    private static final Map<Long, Integer>   NAME_VALUE_INDEX_MAP;

    /** The static table entries (0-indexed, per RFC 9204 Appendix A). */
    private static final QpackHeaderField[] STATIC_TABLE = { new QpackHeaderField(":authority", ""),                                  // 0
            new QpackHeaderField(":path", "/"),                                      // 1
            new QpackHeaderField("age", "0"),                                        // 2
            new QpackHeaderField("content-disposition", ""),                          // 3
            new QpackHeaderField("content-length", "0"),                              // 4
            new QpackHeaderField("cookie", ""),                                       // 5
            new QpackHeaderField("date", ""),                                         // 6
            new QpackHeaderField("etag", ""),                                         // 7
            new QpackHeaderField("if-modified-since", ""),                             // 8
            new QpackHeaderField("if-none-match", ""),                                 // 9
            new QpackHeaderField("last-modified", ""),                                 // 10
            new QpackHeaderField("link", ""),                                          // 11
            new QpackHeaderField("location", ""),                                      // 12
            new QpackHeaderField("referer", ""),                                       // 13
            new QpackHeaderField("set-cookie", ""),                                    // 14
            new QpackHeaderField(":method", "CONNECT"),                                // 15
            new QpackHeaderField(":method", "DELETE"),                                 // 16
            new QpackHeaderField(":method", "GET"),                                    // 17
            new QpackHeaderField(":method", "HEAD"),                                   // 18
            new QpackHeaderField(":method", "OPTIONS"),                                // 19
            new QpackHeaderField(":method", "POST"),                                   // 20
            new QpackHeaderField(":method", "PUT"),                                    // 21
            new QpackHeaderField(":scheme", "http"),                                   // 22
            new QpackHeaderField(":scheme", "https"),                                  // 23
            new QpackHeaderField(":status", "103"),                                    // 24
            new QpackHeaderField(":status", "200"),                                    // 25
            new QpackHeaderField(":status", "304"),                                    // 26
            new QpackHeaderField(":status", "404"),                                    // 27
            new QpackHeaderField(":status", "503"),                                    // 28
            new QpackHeaderField("accept", "*/*"),                                     // 29
            new QpackHeaderField("accept", "application/dns-message"),                 // 30
            new QpackHeaderField("accept-encoding", "gzip, deflate, br"),              // 31
            new QpackHeaderField("accept-ranges", "bytes"),                            // 32
            new QpackHeaderField("access-control-allow-headers", "cache-control"),     // 33
            new QpackHeaderField("access-control-allow-headers", "content-type"),      // 34
            new QpackHeaderField("access-control-allow-origin", "*"),                  // 35
            new QpackHeaderField("cache-control", "max-age=0"),                        // 36
            new QpackHeaderField("cache-control", "max-age=2592000"),                  // 37
            new QpackHeaderField("cache-control", "max-age=604800"),                   // 38
            new QpackHeaderField("cache-control", "no-cache"),                         // 39
            new QpackHeaderField("cache-control", "no-store"),                         // 40
            new QpackHeaderField("cache-control", "public, max-age=31536000"),         // 41
            new QpackHeaderField("content-encoding", "br"),                            // 42
            new QpackHeaderField("content-encoding", "gzip"),                          // 43
            new QpackHeaderField("content-type", "application/dns-message"),           // 44
            new QpackHeaderField("content-type", "application/javascript"),            // 45
            new QpackHeaderField("content-type", "application/json"),                  // 46
            new QpackHeaderField("content-type", "application/x-www-form-urlencoded"), // 47
            new QpackHeaderField("content-type", "image/gif"),                         // 48
            new QpackHeaderField("content-type", "image/jpeg"),                        // 49
            new QpackHeaderField("content-type", "image/png"),                         // 50
            new QpackHeaderField("content-type", "text/css"),                          // 51
            new QpackHeaderField("content-type", "text/html; charset=utf-8"),          // 52
            new QpackHeaderField("content-type", "text/plain"),                        // 53
            new QpackHeaderField("content-type", "text/plain;charset=utf-8"),          // 54
            new QpackHeaderField("range", "bytes=0-"),                                 // 55
            new QpackHeaderField("strict-transport-security", "max-age=31536000"),     // 56
            new QpackHeaderField("strict-transport-security", "max-age=31536000; includesubdomains"), // 57
            new QpackHeaderField("strict-transport-security", "max-age=31536000; includesubdomains; preload"), // 58
            new QpackHeaderField("vary", "accept-encoding"),                           // 59
            new QpackHeaderField("vary", "origin"),                                    // 60
            new QpackHeaderField("x-content-type-options", "nosniff"),                 // 61
            new QpackHeaderField("x-xss-protection", "1; mode=block"),                 // 62
            new QpackHeaderField(":status", "100"),                                    // 63
            new QpackHeaderField(":status", "204"),                                    // 64
            new QpackHeaderField(":status", "206"),                                    // 65
            new QpackHeaderField(":status", "302"),                                    // 66
            new QpackHeaderField(":status", "400"),                                    // 67
            new QpackHeaderField(":status", "403"),                                    // 68
            new QpackHeaderField(":status", "421"),                                    // 69
            new QpackHeaderField(":status", "425"),                                    // 70
            new QpackHeaderField(":status", "500"),                                    // 71
            new QpackHeaderField("accept-language", ""),                               // 72
            new QpackHeaderField("access-control-allow-credentials", "FALSE"),         // 73
            new QpackHeaderField("access-control-allow-credentials", "TRUE"),          // 74
            new QpackHeaderField("access-control-allow-headers", "*"),                 // 75
            new QpackHeaderField("access-control-allow-methods", "get"),               // 76
            new QpackHeaderField("access-control-allow-methods", "get, post, options"), // 77
            new QpackHeaderField("access-control-allow-methods", "options"),            // 78
            new QpackHeaderField("access-control-expose-headers", "content-length"),   // 79
            new QpackHeaderField("access-control-request-headers", "content-type"),    // 80
            new QpackHeaderField("access-control-request-method", "get"),              // 81
            new QpackHeaderField("access-control-request-method", "post"),             // 82
            new QpackHeaderField("alt-svc", "clear"),                                  // 83
            new QpackHeaderField("authorization", ""),                                 // 84
            new QpackHeaderField("content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'"), // 85
            new QpackHeaderField("early-data", "1"),                                   // 86
            new QpackHeaderField("expect-ct", ""),                                     // 87
            new QpackHeaderField("forwarded", ""),                                     // 88
            new QpackHeaderField("if-range", ""),                                      // 89
            new QpackHeaderField("origin", ""),                                        // 90
            new QpackHeaderField("purpose", "prefetch"),                               // 91
            new QpackHeaderField("server", ""),                                        // 92
            new QpackHeaderField("timing-allow-origin", "*"),                           // 93
            new QpackHeaderField("upgrade-insecure-requests", "1"),                    // 94
            new QpackHeaderField("user-agent", ""),                                    // 95
            new QpackHeaderField("x-forwarded-for", ""),                               // 96
            new QpackHeaderField("x-frame-options", "deny"),                           // 97
            new QpackHeaderField("x-frame-options", "sameorigin"),                     // 98
    };

    static {
        // Build HashMap indexes for O(1) lookup
        NAME_INDEX_MAP = new HashMap<>(128);
        NAME_VALUE_INDEX_MAP = new HashMap<>(128);
        for (int i = 0; i < STATIC_TABLE.length; i++) {
            QpackHeaderField f = STATIC_TABLE[i];
            NAME_INDEX_MAP.putIfAbsent(f.name(), i);
            NAME_VALUE_INDEX_MAP.putIfAbsent(nameValueKey(f.name(), f.value()), i);
        }
    }

    private static long nameValueKey(String name, String value) {
        return ((long) name.hashCode() << 32) | (value.hashCode() & 0xFFFFFFFFL);
    }

    private QpackStaticTable() {
    }

    /** Returns the number of entries in the static table. */
    public static int length() {
        return STATIC_TABLE.length;
    }

    /**
     * Returns the static table entry at the given index.
     * @param index 0-based index
     * @return the header field
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static QpackHeaderField get(int index) {
        return STATIC_TABLE[index];
    }

    /**
     * Finds the index of a header field in the static table.
     * O(1) via HashMap; falls back to linear scan on hash collision.
     * @param name the header name (lowercase)
     * @param value the header value
     * @return the index, or -1 if not found
     */
    public static int findIndex(String name, String value) {
        long key = nameValueKey(name, value);
        Integer idx = NAME_VALUE_INDEX_MAP.get(key);
        if (idx != null) {
            QpackHeaderField f = STATIC_TABLE[idx];
            if (f.name().equals(name) && f.value().equals(value)) {
                return idx;
            }
            // Hash collision - fallback to linear scan (extremely rare)
            for (int i = 0; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i].name().equals(name) && STATIC_TABLE[i].value().equals(value)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Finds the index of a header name in the static table (name-only match).
     * O(1) via HashMap.
     * @param name the header name (lowercase)
     * @return the index of the first match, or -1 if not found
     */
    public static int findNameIndex(String name) {
        Integer idx = NAME_INDEX_MAP.get(name);
        return idx != null ? idx : -1;
    }
}
