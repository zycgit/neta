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
package net.hasor.neta.codec.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import okhttp3.*;

public class RealAsServerTest extends AbstractHttpTest {
    private static OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()                   //
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(5, TimeUnit.SECONDS)   //
                .writeTimeout(5, TimeUnit.SECONDS)  //
                .build();
    }

    private static ProtoInitializer buildServerProto() {
        return ProtoHelper.standard()//
                .nextDuplex("http-codec", new HttpServerDuplexe())//
                .nextDecoder("http-aggregator", new HttpRequestAggregator(1024 * 1024))//
                .nextDecoder("http-handler", new InlineHttpServerHandler())//
                .build();
    }

    private static class InlineHttpServerHandler implements ProtoHandler<HttpObject, Object> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                Object msg = ((ProtoRcvQueue) src).takeMessage();
                if (!(msg instanceof FullHttpRequest)) {
                    continue;
                }
                FullHttpRequest request = (FullHttpRequest) msg;
                DefaultFullHttpResponse response = buildResponse(request);
                response.streamId(request.streamId());
                context.sendData(response).get();
            }
            return ProtoStatus.Next;
        }

        private DefaultFullHttpResponse buildResponse(FullHttpRequest request) {
            String body;
            String contentType = "text/plain";
            if ("/hello".equals(request.uri())) {
                body = "hello-neta";
            } else if ("/form".equals(request.uri())) {
                body = readBody(request);
            } else if ("/upload".equals(request.uri())) {
                String requestBody = readBody(request);
                body = request.getString(HttpHeaderNames.CONTENT_TYPE) + "|" + (requestBody.contains("demo-upload") ? "demo-upload" : "") + "|" + (requestBody.contains("Hello Upload") ? "Hello Upload" : "");
            } else {
                body = "not-found";
            }

            ByteBuf bodyBuf = ByteBuf.wrap(body.getBytes(StandardCharsets.UTF_8));
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
            response.setHeader(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBuf.readableBytes()));
            return response;
        }

        private String readBody(FullHttpRequest request) {
            ByteBuf content = request.content();
            if (content == null || content.readableBytes() == 0) {
                return "";
            }
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(0, bytes, 0, bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testNetaServerHandlesBasicGetWithOkHttp() throws Exception {
        // server
        int port = findFreePort();
        NetManager neta = new NetManager();
        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(), SoConfig.TCP());
        // client
        OkHttpClient client = okHttpClient();

        try {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/hello").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                assertEquals("hello-neta", response.body().string());
                assertEquals("text/plain", response.header("Content-Type"));
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerHandlesFormPostWithOkHttp() throws Exception {
        // server
        int port = findFreePort();
        NetManager neta = new NetManager();
        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(), SoConfig.TCP());
        // client
        OkHttpClient client = okHttpClient();

        try {
            RequestBody form = new FormBody.Builder().add("name", "alice").add("city", "hangzhou").build();
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/form").post(form).build();

            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                assertEquals("name=alice&city=hangzhou", response.body().string());
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerHandlesMultipartUploadWithOkHttp() throws Exception {
        // server
        int port = findFreePort();
        NetManager neta = new NetManager();
        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(), SoConfig.TCP());
        // client
        OkHttpClient client = okHttpClient();

        try {
            RequestBody upload = new MultipartBody.Builder().setType(MultipartBody.FORM)//
                    .addFormDataPart("desc", "demo-upload") //
                    .addFormDataPart("file", "hello.txt", //
                            RequestBody.create("Hello Upload", MediaType.parse("text/plain")))//
                    .build();
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload").post(upload).build();

            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                String body = response.body().string();
                assertTrue(body.startsWith("multipart/form-data; boundary="));
                assertTrue(body.contains("|demo-upload|Hello Upload"));
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }
}