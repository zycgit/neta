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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;

import org.junit.Test;

import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpObject;

public class ContentBodyBuilderTest extends RequestHttpObjectTestSupport {
    @Test
    public void testTextBodyBuilderBuildsRequestBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/body/text")
                .post(TextBody.builder().text("payload").build())
                .build());
        // @formatter:on
        try {
            assertEquals("payload", aggregateText(objects));
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("text/plain"));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testFormBodyBuilderBuildsRequestBody() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/body/form")
                .post(FormBody.builder().addField("name", "zyc").addField("city", "hz").build())
                .build());
        // @formatter:on
        try {
            assertEquals("name=zyc&city=hz", aggregateText(objects));
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("application/x-www-form-urlencoded"));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testMultipartBodyBuilderBuildsRequestBody() throws Exception {
    // @formatter:off
        MultipartBody body = MultipartBody.builder()
                .addPart(MultipartPart.builder().field("note", "memo").build())
                .addPart(MultipartPart.builder().file("file", "demo.txt", TextBody.builder().text("ABC").build()).build())
                .build();

        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/body/multipart")
                .post(body)
                .build());
    // @formatter:on
        try {
            String text = aggregateText(objects);
            assertTrue(headers(objects).getString(HttpHeaderNames.CONTENT_TYPE).startsWith("multipart/form-data; boundary="));
            assertTrue(text.contains("name=\"note\""));
            assertTrue(text.contains("memo"));
            assertTrue(text.contains("filename=\"demo.txt\""));
            assertTrue(text.contains("ABC"));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testStreamBodyBuilderBuildsRequestBody() throws Exception {
        byte[] payload = "stream-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/body/stream")
                .post(StreamBody.builder().inputStream(new ByteArrayInputStream(payload)).contentLength(payload.length).contentType("application/custom").build())
                .build());
        // @formatter:on
        try {
            assertEquals("stream-payload", aggregateText(objects));
            assertEquals(String.valueOf(payload.length), headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("application/custom", headers(objects).getString(HttpHeaderNames.CONTENT_TYPE));
        } finally {
            release(objects);
        }
    }
}