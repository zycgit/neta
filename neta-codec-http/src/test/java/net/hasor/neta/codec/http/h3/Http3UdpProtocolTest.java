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
package net.hasor.neta.codec.http.h3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.codec.http.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * HTTP/3 protocol verification tests using real UDP sockets (neta-to-neta).
 * <p>
 * Starts a real Neta UDP server with {@link Http3ServerDuplexe} and connects
 * a Neta UDP client with {@link Http3ClientDuplexe}. Verifies end-to-end HTTP/3
 * binary framing and QPACK header compression over actual UDP network I/O.
 * <p>
 * Unlike VrtChannel-based tests, this uses real OS-level UDP datagram sockets,
 * proving the HTTP/3 codec works over genuine network transport.
 */
public class Http3UdpProtocolTest {

    private NetManager neta;
    private int        port;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static ByteBuf toBody(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() + 16);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return buf;
    }

    private static String readBody(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0)
            return "";
        return buf.readString(buf.readableBytes(), StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() throws Exception {
        Thread.sleep(50);
        port = findFreePort();
        neta = new NetManager();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    /**
     * Starts a UDP server with Http3ServerDuplexe codec.
     */
    private void startH3Server() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("h3-codec", new Http3ServerDuplexe());
            ctx.addLastDecoder("h3-aggregator", new HttpObjectAggregator(1048576));
        };
        UdpSoConfig udpConfig = SoConfig.UDP();
        udpConfig.setRcvPacketSize(65535);
        neta.bind(new InetSocketAddress("127.0.0.1", port), serverProto, udpConfig);
    }

    /**
     * Creates a UDP client with Http3ClientDuplexe codec.
     */
    private NetChannel connectH3Client() throws Exception {
        ProtoInitializer clientProto = ctx -> {
            ctx.addLast("h3-codec", new Http3ClientDuplexe());
            ctx.addLastDecoder("h3-aggregator", new HttpObjectAggregator(1048576));
        };
        UdpSoConfig udpConfig = SoConfig.UDP();
        udpConfig.setRcvPacketSize(65535);
        return neta.connectSync(new InetSocketAddress("127.0.0.1", port), clientProto, udpConfig);
    }

    // ========================= GET — end-to-end over real UDP =========================

    @Test
    public void testH3Udp_GetRequest() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                FullHttpRequest req = (FullHttpRequest) data;
                ByteBuf body = toBody("Hello HTTP/3 over UDP!");
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, body);
                response.headers().set("content-type", "text/plain");
                response.headers().set("content-length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            Object obj = d.getData();
            if (obj instanceof FullHttpResponse) {
                future.complete((FullHttpResponse) obj);
            }
        });

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/index");
        request.headers().add("host", "www.example.com");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull("Should receive HTTP/3 response over real UDP", response);
        assertEquals(200, response.status().code());
        assertEquals("Hello HTTP/3 over UDP!", readBody(response.content()));
    }

    // ========================= POST with JSON body =========================

    @Test
    public void testH3Udp_PostJsonBody() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                FullHttpRequest req = (FullHttpRequest) data;
                assertEquals(HttpMethod.POST, req.method());

                ByteBuf content = req.content();
                int size = content != null ? content.readableBytes() : 0;
                ByteBuf body = toBody("{\"received\":" + size + "}");
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, body);
                response.headers().set("content-type", "application/json");
                response.headers().set("content-length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        ByteBuf reqBody = toBody("{\"key\":\"value\"}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/api/data", reqBody);
        request.headers().add("host", "api.example.com");
        request.headers().add("content-type", "application/json");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.status().code());
        String respText = readBody(response.content());
        assertTrue("Should contain received body size", respText.contains("\"received\":15"));
    }

    // ========================= Custom headers round-trip =========================

    @Test
    public void testH3Udp_CustomHeaders() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                FullHttpRequest req = (FullHttpRequest) data;
                String id = req.headers().get("x-request-id");

                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
                response.headers().set("x-echo-id", id != null ? id : "null");
                response.headers().set("x-protocol", "h3");
                response.headers().set("content-length", "0");
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/custom");
        request.headers().add("host", "example.com");
        request.headers().add("x-request-id", "udp-h3-001");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertEquals("udp-h3-001", response.headers().get("x-echo-id"));
        assertEquals("h3", response.headers().get("x-protocol"));
    }

    // ========================= Status 404 =========================

    @Test
    public void testH3Udp_StatusCode404() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                ByteBuf body = toBody("Not Found");
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NOT_FOUND, body);
                response.headers().set("content-type", "text/plain");
                response.headers().set("content-length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/missing");
        request.headers().add("host", "example.com");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(404, response.status().code());
        assertEquals("Not Found", readBody(response.content()));
    }

    // ========================= Status 500 =========================

    @Test
    public void testH3Udp_StatusCode500() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                ByteBuf body = toBody("Server Error");
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.INTERNAL_SERVER_ERROR, body);
                response.headers().set("content-type", "text/plain");
                response.headers().set("content-length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/error");
        request.headers().add("host", "example.com");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(500, response.status().code());
    }

    // ========================= DELETE with 204 =========================

    @Test
    public void testH3Udp_DeleteWith204() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NO_CONTENT);
                response.headers().set("content-length", "0");
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.DELETE, "/item/99");
        request.headers().add("host", "example.com");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(204, response.status().code());
    }

    // ========================= PUT request =========================

    @Test
    public void testH3Udp_PutRequest() throws Exception {
        startH3Server();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object data = payload.getData();
            if (data instanceof FullHttpRequest) {
                ByteBuf body = toBody("{\"updated\":true}");
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, body);
                response.headers().set("content-type", "application/json");
                response.headers().set("content-length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });
        Thread.sleep(300);

        NetChannel client = connectH3Client();
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        client.subscribe(PlayLoad::isInbound, d -> {
            if (d.getData() instanceof FullHttpResponse)
                future.complete((FullHttpResponse) d.getData());
        });

        ByteBuf reqBody = toBody("{\"name\":\"new\"}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.PUT, "/item/7", reqBody);
        request.headers().add("host", "api.example.com");
        request.headers().add("content-type", "application/json");
        client.sendData(request);

        FullHttpResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertTrue(readBody(response.content()).contains("\"updated\":true"));
    }
}
