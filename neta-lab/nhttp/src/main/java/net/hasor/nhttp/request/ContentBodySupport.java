/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;

import net.hasor.neta.bytebuf.ByteBuf;

final class ContentBodySupport {
    private ContentBodySupport() {
    }

    public static Map<String, List<String>> copyFields(Map<String, List<String>> fields) {
        Map<String, List<String>> target = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            target.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return target;
    }

    public static byte[] encodeForm(Map<String, List<String>> fields, Charset charset) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            for (String value : entry.getValue()) {
                if (!first) {
                    builder.append('&');
                }
                builder.append(urlEncode(entry.getKey(), charset)).append('=').append(urlEncode(value, charset));
                first = false;
            }
        }

        return builder.toString().getBytes(charset);
    }

    public static String urlEncode(String value, Charset charset) {
        try {
            return URLEncoder.encode(value == null ? "" : value, charset.name());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String charsetName(Charset charset) {
        return charset.name().toLowerCase(Locale.ENGLISH);
    }

    public static List<ByteBuf> readStreamParts(InputStream inputStream, int chunkSize) throws IOException {
        List<ByteBuf> parts = new ArrayList<>();
        byte[] buffer = new byte[chunkSize];
        int len;
        while ((len = inputStream.read(buffer)) >= 0) {
            if (len == 0) {
                continue;
            }
            byte[] copy = new byte[len];
            System.arraycopy(buffer, 0, copy, 0, len);
            parts.add(ByteBuf.wrap(copy));
        }

        return parts;
    }

    public static void copyToSwapFileBuffer(InputStream inputStream, ByteBuf buffer, int chunkSize) throws IOException {
        byte[] bytes = new byte[chunkSize];
        int len;
        while ((len = inputStream.read(bytes)) >= 0) {
            if (len == 0) {
                continue;
            }
            buffer.writeBytes(bytes, 0, len);
        }
    }

    public static byte[] flatten(List<ByteBuf> parts) throws IOException {
        if (parts == null || parts.isEmpty()) {
            return new byte[0];
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (ByteBuf part : parts) {
            if (part == null || part.readableBytes() == 0) {
                continue;
            }
            byte[] buffer = new byte[part.readableBytes()];
            part.getBytes(part.readerIndex(), buffer, 0, buffer.length);
            outputStream.write(buffer);
        }

        return outputStream.toByteArray();
    }
}
