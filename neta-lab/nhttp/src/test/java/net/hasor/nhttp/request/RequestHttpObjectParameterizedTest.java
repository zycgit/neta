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
import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpObject;

public class RequestHttpObjectParameterizedTest extends RequestHttpObjectTestSupport {
    @Test
    public void testRequestCarriesHeadersCookiesAndParameters() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/parameter/base")
                .addQueryParameter("page", "1")
                .addHeader("x-trace-id", "trace-001")
                .cookie("sid", "abc")
                .cookie("theme", "dark")
                .get()
                .build());
        // @formatter:on
        try {
            assertEquals("/parameter/base?page=1", requestLine(objects).uri());
            assertEquals("trace-001", headers(objects).getString("x-trace-id"));
            assertEquals("sid=abc; theme=dark", headers(objects).getString(HttpHeaderNames.COOKIE));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testFormRequestBuildsExpectedBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/form")
                .post(ContentBody.form(f -> {
                    f.add("name", "zyc")
                     .add("city", "hz");
                })).build());
        // @formatter:on
        try {
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("application/x-www-form-urlencoded"));
            assertEquals("name=zyc&city=hz", aggregateText(objects));
            assertEquals("16", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testMultipartRequestBuildsExpectedBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/upload")
                .post(ContentBody.multipart(multipart -> multipart.field("note", "memo").file("file", "demo.txt", "ABC".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .build());
        // @formatter:on
        try {
            String body = aggregateText(objects);
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("multipart/form-data; boundary="));
            assertTrue(body.contains("name=\"note\""));
            assertTrue(body.contains("memo"));
            assertTrue(body.contains("filename=\"demo.txt\""));
            assertTrue(body.contains("ABC"));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testBinaryByteArrayBodyUsesKnownLength() throws Exception {
        byte[] payload = new byte[] { 1, 2, 3, 4, 5, 6 };
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/binary/bytes")
                .contentType("application/octet-stream")
                .post(ContentBody.bytes(payload))
                .build());
        // @formatter:on
        try {
            assertEquals("6", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("application/octet-stream", headers(objects).getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals(1L, aggregateContent(objects)[0]);
            assertEquals(6L, aggregateContent(objects)[5]);
        } finally {
            release(objects);
        }
    }

    @Test
    public void testExplicitHeadersAreNotOverriddenByAutoPopulation() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1:8080/header/override")
                .header(HttpHeaderNames.HOST, "gateway.example")
                .header(HttpHeaderNames.CONTENT_TYPE, "application/custom")
                .header(HttpHeaderNames.CONTENT_LENGTH, "999")
                .post(ContentBody.text("abc"))
                .build());
        // @formatter:on
        try {
            assertEquals("gateway.example", headers(objects).getString(HttpHeaderNames.HOST));
            assertEquals("application/custom", headers(objects).getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("999", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testDuplicateHeadersPreservedInInsertionOrder() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/header/repeat")
                .addHeader("x-repeat", "one")
                .addHeader("x-repeat", "two")
                .get()
                .build());
        // @formatter:on
        try {
            assertEquals(Arrays.asList("one", "two"), headers(objects).getValues("x-repeat"));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testExplicitTransferEncodingSuppressesAutoContentLength() throws Exception {
        byte[] payload = new byte[] { 1, 2, 3 };
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/header/transfer-encoding")
                .header(HttpHeaderNames.TRANSFER_ENCODING, "gzip")
                .post(ContentBody.bytes(payload))
                .build());
        // @formatter:on
        try {
            assertEquals("gzip", headers(objects).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONTENT_LENGTH));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testExplicitContentLengthSuppressesAutoChunkedForUnknownLengthBody() throws Exception {
        byte[] payload = "payload-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/header/content-length")
                .header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(payload.length))
                .post(ContentBody.stream(() -> new java.io.ByteArrayInputStream(payload)))
                .build());
        // @formatter:on
        try {
            assertEquals(String.valueOf(payload.length), headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("payload-body", aggregateText(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testEncodedQueryParameterIsPreservedVerbatim() throws Exception {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder("http://127.0.0.1/query/encoded")
                .addEncodedQueryParameter("redirect", "https%3A%2F%2Fexample.com%2Fa%2Bb")
                .build();
        HttpObject[] objects = write(new Request.Builder()
                .url(httpUrl)
                .get()
                .build());
        // @formatter:on
        try {
            assertEquals("/query/encoded?redirect=https%3A%2F%2Fexample.com%2Fa%2Bb", requestLine(objects).uri());
        } finally {
            release(objects);
        }
    }
}