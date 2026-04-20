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

import org.junit.Test;

import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpVersion;

public class RequestHttpObjectBasicTest extends RequestHttpObjectTestSupport {
    @Test
    public void testGetRequestWithoutBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/get")//
                .get()//
                .build());
        // @formatter:on
        try {
            assertEquals(HttpMethod.GET, requestLine(objects).method());
            assertEquals("/basic/get", requestLine(objects).uri());
            assertEquals("127.0.0.1", headers(objects).getString(HttpHeaderNames.HOST));
            assertEquals("0", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals(3, objects.length);
            assertEquals(0, contents(objects).get(0).content().readableBytes());
        } finally {
            release(objects);
        }
    }

    @Test
    public void testDeleteRequestWithoutBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/item/1")//
                .delete()//
                .build());
        // @formatter:on
        try {
            assertEquals(HttpMethod.DELETE, requestLine(objects).method());
            assertEquals("/basic/item/1", requestLine(objects).uri());
            assertEquals("0", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testPostRequestWithBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/post")//
                .post(ContentBody.text("payload"))//
                .build());
        // @formatter:on
        try {
            assertEquals(HttpMethod.POST, requestLine(objects).method());
            assertEquals("/basic/post", requestLine(objects).uri());
            assertEquals("7", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("text/plain"));
            assertEquals("payload", aggregateText(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testUrlEncodingQueryParameters() throws Exception {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder("http://127.0.0.1/basic/query")//
                .addQueryParameter("name", "冯.诺伊曼")//
                .addQueryParameter("city", "hz west")//
                .build();
        HttpObject[] objects = write(new Request.Builder()//
                .url(httpUrl)//
                .get()//
                .build());
        // @formatter:on
        try {
            assertEquals("/basic/query?name=%E5%86%AF.%E8%AF%BA%E4%BC%8A%E6%9B%BC&city=hz+west", requestLine(objects).uri());
        } finally {
            release(objects);
        }
    }

    @Test
    public void testOriginFormFallsBackToSlashAndDropsFragment() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1?alpha=1#tail")//
                .get()//
                .build());
        // @formatter:on
        try {
            assertEquals("/?alpha=1", requestLine(objects).uri());
            assertEquals("127.0.0.1", headers(objects).getString(HttpHeaderNames.HOST));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testHeadRequestStillEmitsTerminalLastContent() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/head")//
                .method(HttpMethod.HEAD)//
                .build());
        // @formatter:on
        try {
            assertEquals(HttpMethod.HEAD, requestLine(objects).method());
            assertEquals("/basic/head", requestLine(objects).uri());
            assertEquals(1, contents(objects).size());
            assertEquals(0, contents(objects).get(0).content().readableBytes());
        } finally {
            release(objects);
        }
    }

    @Test
    public void testDefaultPortIsOmittedFromHostHeader() throws Exception {
        // @formatter:off
        HttpObject[] httpObjects = write(new Request.Builder()//
                .url("http://example.com:80/basic/host")//
                .get()//
                .build());
        HttpObject[] httpsObjects = write(new Request.Builder()//
                .url("https://example.com:443/basic/host")//
                .get()//
                .build());
        // @formatter:on
        try {
            assertEquals("example.com", headers(httpObjects).getString(HttpHeaderNames.HOST));
            assertEquals("example.com", headers(httpsObjects).getString(HttpHeaderNames.HOST));
        } finally {
            release(httpObjects);
            release(httpsObjects);
        }
    }

    @Test
    public void testNonDefaultPortIsRetainedInHostHeader() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("https://example.com:8443/basic/host")//
                .get()//
                .build());
        // @formatter:on
        try {
            assertEquals("example.com:8443", headers(objects).getString(HttpHeaderNames.HOST));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testCustomMethodAndProtocolVersionAreSerialized() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/custom")//
                .method(new HttpMethod("PROPFIND"))//
                .build(), HttpVersion.HTTP_1_0);
        // @formatter:on
        try {
            assertEquals("PROPFIND", requestLine(objects).method().name());
            assertEquals(HttpVersion.HTTP_1_0.text(), requestLine(objects).protocolVersion().text());
            assertEquals("/basic/custom", requestLine(objects).uri());
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONNECTION));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testHttp11AddsDefaultKeepAliveHeader() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()//
                .url("http://127.0.0.1/basic/http11")//
                .get()//
                .build(), HttpVersion.HTTP_1_1);
        // @formatter:on
        try {
            assertEquals("keep-alive", headers(objects).getString(HttpHeaderNames.CONNECTION));
        } finally {
            release(objects);
        }
    }
}