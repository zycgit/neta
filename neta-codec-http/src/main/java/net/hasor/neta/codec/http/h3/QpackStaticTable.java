/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import java.util.HashMap;
import java.util.Map;
/**
 * RFC 9204 附录 A 定义的 QPACK 静态表。
 * <p>
 * 静态表包含 99 个 HTTP/3 中常用的预定义 header field。该表对所有连接都相同，且永不变化。
 * 与 HPACK 静态表不同，QPACK 静态表使用 0 基索引。
 * <p>
 * 这里使用基于 HashMap 的查找，将名称和名称+值匹配从 O(99) 线性扫描优化为 O(1)。
 */
public final class QpackStaticTable {
    /**
     * 从 header 名称映射到首个匹配索引的表，使用 0 基索引。
     */
    private static final Map<String, Integer> NAME_INDEX_MAP;
    /**
     * 从名称+值组合键映射到精确匹配索引的表，使用 0 基索引。
     */
    private static final Map<Long, Integer>   NAME_VALUE_INDEX_MAP;

    /**
     * 静态表条目，按 RFC 9204 附录 A 使用 0 基索引。
     */
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
        // 构建 HashMap 索引，用于 O(1) 查找。
        NAME_INDEX_MAP = new HashMap<>(128);
        NAME_VALUE_INDEX_MAP = new HashMap<>(128);
        for (int i = 0; i < STATIC_TABLE.length; i++) {
            QpackHeaderField f = STATIC_TABLE[i];
            NAME_INDEX_MAP.putIfAbsent(f.name(), i);
            NAME_VALUE_INDEX_MAP.putIfAbsent(nameValueKey(f.name(), f.value()), i);
        }
    }

    private QpackStaticTable() {
    }

    private static long nameValueKey(String name, String value) {
        return ((long) name.hashCode() << 32) | (value.hashCode() & 0xFFFFFFFFL);
    }

    /**
     * 返回静态表中的条目数量。
     */
    public static int length() {
        return STATIC_TABLE.length;
    }

    /**
     * 返回指定索引处的静态表条目。
     * @param index 0 基索引
     * @return header field 条目
     * @throws IndexOutOfBoundsException 当索引越界时抛出
     */
    public static QpackHeaderField get(int index) {
        return STATIC_TABLE[index];
    }

    /**
     * 查找某个 header field 在静态表中的索引。
     * 正常情况下通过 HashMap 实现 O(1)；若发生哈希碰撞，则回退为线性扫描。
     * @param name header 名称，小写
     * @param value header 值
     * @return 匹配索引，未命中时返回 -1
     */
    public static int findIndex(String name, String value) {
        long key = nameValueKey(name, value);
        Integer idx = NAME_VALUE_INDEX_MAP.get(key);
        if (idx != null) {
            QpackHeaderField f = STATIC_TABLE[idx];
            if (f.name().equals(name) && f.value().equals(value)) {
                return idx;
            }
            // 哈希碰撞时回退为线性扫描，这种情况极少发生。
            for (int i = 0; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i].name().equals(name) && STATIC_TABLE[i].value().equals(value)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 查找某个 header 名称在静态表中的索引，只按名称匹配。
     * 该查找通过 HashMap 实现 O(1)。
     * @param name header 名称，小写
     * @return 首个匹配索引，未命中时返回 -1
     */
    public static int findNameIndex(String name) {
        Integer idx = NAME_INDEX_MAP.get(name);
        return idx != null ? idx : -1;
    }
}
