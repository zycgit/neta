/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.*;

public abstract class RequestHttpObjectTestSupport {
    protected static HttpObject[] write(Request request) throws Exception {
        return write(request, HttpVersion.HTTP_1_1);
    }

    protected static HttpObject[] write(Request request, HttpVersion version) throws Exception {
        return new HttpObjectWriter().write(request, version);
    }

    protected static HttpRequest requestLine(HttpObject[] objects) {
        return (HttpRequest) objects[0];
    }

    protected static HttpHeaders headers(HttpObject[] objects) {
        return (HttpHeaders) objects[1];
    }

    protected static List<HttpContent> contents(HttpObject[] objects) {
        ArrayList<HttpContent> result = new ArrayList<>();
        for (int i = 2; i < objects.length; i++) {
            result.add((HttpContent) objects[i]);
        }
        return result;
    }

    protected static byte[] aggregateContent(HttpObject[] objects) throws Exception {
        List<HttpContent> contents = contents(objects);
        int total = 0;
        for (HttpContent content : contents) {
            total += content.content().readableBytes();
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (HttpContent content : contents) {
            ByteBuf body = content.content();
            int readable = body.readableBytes();
            body.getBytes(body.readerIndex(), result, offset, readable);
            offset += readable;
        }
        return result;
    }

    protected static String aggregateText(HttpObject[] objects) throws Exception {
        return new String(aggregateContent(objects), StandardCharsets.UTF_8);
    }

    protected static String md5Hex(HttpObject[] objects) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        for (HttpContent content : contents(objects)) {
            ByteBuf body = content.content();
            byte[] bytes = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), bytes, 0, bytes.length);
            digest.update(bytes);
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    protected static long crc32(HttpObject[] objects) {
        CRC32 crc32 = new CRC32();
        for (HttpContent content : contents(objects)) {
            ByteBuf body = content.content();
            byte[] bytes = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), bytes, 0, bytes.length);
            crc32.update(bytes, 0, bytes.length);
        }
        return crc32.getValue();
    }

    protected static void release(HttpObject[] objects) {
        if (objects == null) {
            return;
        }
        for (HttpObject object : objects) {
            if (object != null) {
                object.release();
            }
        }
    }
}