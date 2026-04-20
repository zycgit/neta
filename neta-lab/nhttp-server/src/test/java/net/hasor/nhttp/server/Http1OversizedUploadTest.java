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
package net.hasor.nhttp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that oversized HTTP/1.1 uploads terminate promptly instead of hanging the client.
 */
public class Http1OversizedUploadTest {
    private NetManager   neta;
    private NetListen    listen;
    private int          port;
    private OkHttpClient http11Client;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Before
    public void setUp() throws Exception {
        Thread.sleep(50L);
        this.neta = new NetManager();
        this.http11Client = new OkHttpClient.Builder()//
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))//
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(15, TimeUnit.SECONDS)//
                .writeTimeout(15, TimeUnit.SECONDS)//
                .callTimeout(20, TimeUnit.SECONDS)//
                .build();
    }

    @After
    public void tearDown() {
        if (this.http11Client != null) {
            this.http11Client.dispatcher().executorService().shutdown();
            this.http11Client.connectionPool().evictAll();
        }
        if (this.neta != null) {
            try {
                this.neta.shutdown();
            } catch (IOException ignore) {
            }
        }
    }

    private void startHttpServer(int maxContentLength, HttpServlet servlet) throws Exception {
        this.port = findFreePort();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.http2(false);
        httpServer.maxContentLength(maxContentLength);
        httpServer.addServlet("/upload", servlet);
        httpServer.initServletContext();

        ProtoInitializer init = httpServer.createHttpInitializer(false);
        this.listen = this.neta.bind(new InetSocketAddress("127.0.0.1", this.port), init, SoConfig.TCP());
    }

    @Test
    public void testHttp1OversizedUploadReturns413Promptly() throws Exception {
        startHttpServer(64 * 1024, new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                req.getBody();
                resp.setContentType("text/plain");
                resp.write("unexpected");
            }
        });

        byte[] body = new byte[256 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('A' + (i % 26));
        }

        Request request = new Request.Builder()//
                .url("http://127.0.0.1:" + this.port + "/upload")//
                .post(RequestBody.create(body, MediaType.parse("application/octet-stream")))//
                .build();

        long startAt = System.nanoTime();
        Response response = this.http11Client.newCall(request).execute();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAt);

        assertEquals(413, response.code());
        assertEquals(Protocol.HTTP_1_1, response.protocol());
        assertTrue("Oversized upload should fail promptly, elapsed=" + elapsedMillis + "ms", elapsedMillis < 10_000L);
        assertNotNull(response.body());
        String responseBody = response.body().string();
        assertTrue(responseBody.contains("413 Payload Too Large"));
    }

    @Test
    public void testHttp1ExpectContinueOversizedUploadRejectedAtHeaders() throws Exception {
        startHttpServer(64 * 1024, new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setStatus(200);
                resp.write("unexpected");
            }
        });

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", this.port), 5_000);
            socket.setSoTimeout(5_000);

            OutputStream out = socket.getOutputStream();
            String request = "POST /upload HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: 297698873\r\n" +
                    "Expect: 100-continue\r\n" +
                    "\r\n";
            out.write(request.getBytes("US-ASCII"));
            out.flush();

            String responseHead = readHttpHead(socket.getInputStream());
            assertTrue("Expected immediate 413 response, actual=" + responseHead, responseHead.contains("HTTP/1.1 413"));
            assertTrue("Expected connection close header, actual=" + responseHead, responseHead.toLowerCase().contains("connection: close"));
        }
    }

    @Test
    public void testHttp1OversizedContentLengthRejectedAtHeaders() throws Exception {
        startHttpServer(64 * 1024, new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setStatus(200);
                resp.write("unexpected");
            }
        });

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", this.port), 5_000);
            socket.setSoTimeout(5_000);

            OutputStream out = socket.getOutputStream();
            String request = "POST /upload HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: 297698873\r\n" +
                    "\r\n";
            out.write(request.getBytes("US-ASCII"));
            out.flush();

            String responseHead = readHttpHead(socket.getInputStream());
            assertTrue("Expected immediate 413 response, actual=" + responseHead, responseHead.contains("HTTP/1.1 413"));
        }
    }

    private static String readHttpHead(InputStream inputStream) throws IOException {
        BufferedInputStream in = new BufferedInputStream(inputStream);
        StringBuilder builder = new StringBuilder();
        int matched = 0;
        int value;
        while ((value = in.read()) >= 0) {
            builder.append((char) value);
            if (matched == 0 && value == '\r') {
                matched = 1;
            } else if (matched == 1 && value == '\n') {
                matched = 2;
            } else if (matched == 2 && value == '\r') {
                matched = 3;
            } else if (matched == 3 && value == '\n') {
                break;
            } else {
                matched = 0;
            }
        }
        return builder.toString();
    }
}