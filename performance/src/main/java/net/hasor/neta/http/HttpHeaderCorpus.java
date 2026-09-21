/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class HttpHeaderCorpus {
    private static final String[]  ORDINARY_NAMES = { "Accept", "Accept-Language", "User-Agent", "Cache-Control", "Content-Type", "Origin", "Referer", "Authorization", "If-None-Match", "Via", "Forwarded", "X-Request-Id", "X-Correlation-Id", "Accept-Encoding", "Pragma", "DNT", "X-Feature-Flags", "X-Client-Version", "X-Tenant", "X-Region", "Traceparent", "Tracestate", "X-Forwarded-For", "X-Forwarded-Proto" };
    final                boolean   response;
    final                Message[] messages       = new Message[16];

    HttpHeaderCorpus(String profile) {
        if (!List.of("ordinary", "sameLength", "mixedCasePost", "fragmentedChunked", "response").contains(profile)) {
            throw new IllegalArgumentException(profile);
        }
        this.response = "response".equals(profile);
        for (int i = 0; i < this.messages.length; i++) {
            this.messages[i] = create(profile, i);
        }
    }

    private Message create(String profile, int variant) {
        boolean chunked = "fragmentedChunked".equals(profile);
        boolean hasBody = this.response || chunked || "mixedCasePost".equals(profile);
        String body = hasBody ? "0123456789abcdef" + "payload-" + variant + "-end" : "";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(this.response ? "Server" : "Host", "corpus.example");
        if (chunked) {
            fields.put("Transfer-Encoding", "chunked");
        } else if (hasBody) {
            fields.put("Content-Length", Integer.toString(body.length()));
        }
        if (this.response) {
            fields.put("Connection", "keep-alive");
        }
        for (int i = 0; fields.size() < 12; i++) {
            String name;
            String value = "value-" + variant + "-" + i;
            if ("sameLength".equals(profile)) {
                name = switch (i) {
                    case 0 -> "Content-Lengtx";
                    case 1 -> "Transfer-Encodinx";
                    case 2 -> "Connectiox";
                    default -> "X-" + "a".repeat(new int[] { 10, 14, 17 }[i % 3] - 3) + (char) ('a' + i);
                };
                if (i < 3) {
                    value = new String[] { "invalid", "chunked", "close" }[i];
                }
            } else if (i % 4 == 3) {
                name = "X-" + "q".repeat(1 + (variant + i) % 25) + i;
            } else {
                name = ORDINARY_NAMES[(variant + i) % ORDINARY_NAMES.length];
            }
            fields.put(name, value);
        }
        List<Map.Entry<String, String>> ordered = new ArrayList<>(fields.entrySet());
        Collections.rotate(ordered, variant % ordered.size());
        StringBuilder wire = new StringBuilder(this.response ? "HTTP/1.1 200 OK\r\n" : (hasBody ? "POST" : "GET") + " /corpus/" + variant + " HTTP/1.1\r\n");
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, String> field = ordered.get(i);
            String name = varyCase(field.getKey(), variant + i);
            wire.append(name).append(": ").append(field.getValue()).append("\r\n");
            expected.put(name.toLowerCase(Locale.ROOT), field.getValue());
        }
        wire.append("\r\n");
        int headerEnd = wire.length();
        if (chunked) {
            wire.append("10\r\n").append(body, 0, 16).append("\r\n");
            wire.append(Integer.toHexString(body.length() - 16)).append("\r\n").append(body.substring(16)).append("\r\n0\r\n\r\n");
        } else {
            wire.append(body);
        }
        byte[] bytes = wire.toString().getBytes(StandardCharsets.US_ASCII);
        byte[][] packets;
        if (chunked) {
            int[] ends = { 13, headerEnd - 9, headerEnd + 11, bytes.length };
            packets = new byte[ends.length][];
            int start = 0;
            for (int i = 0; i < ends.length; i++) {
                packets[i] = Arrays.copyOfRange(bytes, start, ends[i]);
                start = ends[i];
            }
        } else {
            packets = new byte[][] { bytes };
        }
        return new Message(packets, expected, body);
    }

    private static String varyCase(String name, int variant) {
        if (variant % 3 == 0) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (variant % 3 == 1) {
            return name.toUpperCase(Locale.ROOT);
        }
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = i % 2 == 0 ? Character.toLowerCase(chars[i]) : Character.toUpperCase(chars[i]);
        }
        return new String(chars);
    }

    static final class Message {
        final byte[][]            packets;
        final Map<String, String> headers;
        final String              body;

        Message(byte[][] packets, Map<String, String> headers, String body) {
            this.packets = packets;
            this.headers = headers;
            this.body = body;
        }
    }
}
