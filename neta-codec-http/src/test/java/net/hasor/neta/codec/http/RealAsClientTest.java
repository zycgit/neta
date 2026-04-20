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
import static org.junit.Assert.fail;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;
import net.hasor.neta.codec.http.real.httpserver.RawHttpResponse;
import net.hasor.neta.codec.http.real.httpserver.SimpleHttpPeerServer;

public class RealAsClientTest extends AbstractHttpTest {
    private static NetaClientHarness netaClient(NetManager neta, int port) throws Exception {
        return new NetaClientHarness(neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
            ctx.addLast("http-client", new HttpClientDuplexe());
            ctx.addLastDecoder("resp-agg", new HttpResponseAggregator());
        }, SoConfig.TCP()));
    }

    private static final class NetaClientHarness implements Closeable {
        private final NetChannel    channel;
        private final Queue<Object> inbound;

        private NetaClientHarness(NetChannel channel) {
            this.channel = channel;
            this.inbound = new ConcurrentLinkedQueue<>();
            channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                if (d.getData() != null) {
                    inbound.offer(d.getData());
                }
            });
        }

        public FullHttpResponse sendRequest(DefaultFullHttpRequest request, long timeoutMs) throws Exception {
            // send
            this.channel.sendData(request).get();

            // wait
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Object msg = this.inbound.poll();
                if (msg instanceof FullHttpResponse) {
                    return (FullHttpResponse) msg;
                }
                Thread.sleep(10);
            }
            fail("Timed out waiting for FullHttpResponse");
            return null;
        }

        @Override
        public void close() {
            this.channel.close().await();
        }
    }

    //

    @Test
    public void testNetaClientReceivesBasicResponseFromRawHttpServer() throws Exception {
        // server
        int port = findFreePort();
        SimpleHttpPeerServer server = SimpleHttpPeerServer.start(port, request -> {
            assertEquals("GET", request.method);
            assertEquals("/hello", request.path);
            return new RawHttpResponse(200, "OK", "text/plain", "hello-jdk");
        });
        // client
        NetManager neta = new NetManager();
        NetaClientHarness client = netaClient(neta, port);

        try {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello");
            request.addHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);

            FullHttpResponse response = client.sendRequest(request, 5000);
            assertEquals(HttpStatus.OK, response.status());
            assertEquals("hello-jdk", text(response.content().retain()));
            response.release();
        } finally {
            neta.shutdown();
            server.close();
        }
    }

    @Test
    public void testNetaClientPostsFormToRawHttpServer() throws Exception {
        // server
        int port = findFreePort();
        SimpleHttpPeerServer server = SimpleHttpPeerServer.start(port, request -> {
            assertEquals("POST", request.method);
            assertEquals("/form", request.path);
            assertEquals(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED, request.contentType);
            return new RawHttpResponse(200, "OK", "text/plain", "form=" + request.body);
        });
        // client
        NetManager neta = new NetManager();
        NetaClientHarness client = netaClient(neta, port);

        try {
            byte[] formBytes = "name=bob&age=18".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/form", ByteBuf.wrap(formBytes));
            request.addHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
            request.addHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
            request.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(formBytes.length));

            FullHttpResponse response = client.sendRequest(request, 5000);
            assertEquals(HttpStatus.OK, response.status());
            assertEquals("form=name=bob&age=18", text(response.content().retain()));
            response.release();
        } finally {
            neta.shutdown();
            server.close();
        }
    }

    @Test
    public void testNetaClientUploadsMultipartToRawHttpServer() throws Exception {
        // server
        int port = findFreePort();
        SimpleHttpPeerServer server = SimpleHttpPeerServer.start(port, request -> {
            assertEquals("POST", request.method);
            assertEquals("/upload", request.path);
            boolean multipart = request.contentType != null && request.contentType.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA);
            boolean hasDesc = request.body.contains("client-upload");
            boolean hasFile = request.body.contains("Hello Upload");
            return new RawHttpResponse(200, "OK", "text/plain", "multipart=" + multipart + ",desc=" + hasDesc + ",file=" + hasFile);
        });
        // client
        NetManager neta = new NetManager();
        NetaClientHarness client = netaClient(neta, port);

        try {
            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("desc", "client-upload");
            encoder.addFile("file", "hello.txt", "text/plain", "Hello Upload".getBytes(StandardCharsets.UTF_8));
            byte[] bodyBytes = encoder.encode();

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", ByteBuf.wrap(bodyBytes));
            request.addHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
            request.addHeader(HttpHeaderNames.CONTENT_TYPE, encoder.contentType());
            request.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));

            FullHttpResponse response = client.sendRequest(request, 5000);
            assertEquals(HttpStatus.OK, response.status());
            assertEquals("multipart=true,desc=true,file=true", text(response.content().retain()));
            response.release();
        } finally {
            neta.shutdown();
            server.close();
        }
    }

    private static String readBody(FullHttpResponse response) {
        ByteBuf content = response.content();
        if (content == null || content.readableBytes() == 0) {
            return "";
        }
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(0, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

}