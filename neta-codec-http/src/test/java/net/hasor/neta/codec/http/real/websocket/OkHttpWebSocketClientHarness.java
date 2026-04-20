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
package net.hasor.neta.codec.http.real.websocket;

import static org.junit.Assert.*;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.*;
import okio.ByteString;

public class OkHttpWebSocketClientHarness implements Closeable {
    private final OkHttpClient               client;
    private final WebSocket                  webSocket;
    private final CountDownLatch             openLatch;
    private final AtomicReference<Throwable> failure;
    private final BlockingQueue<String>      textQueue;
    private final BlockingQueue<ByteString>  binaryQueue;

    private OkHttpWebSocketClientHarness(OkHttpClient client, WebSocket webSocket, CountDownLatch openLatch, AtomicReference<Throwable> failure, BlockingQueue<String> textQueue, BlockingQueue<ByteString> binaryQueue) {
        this.client = client;
        this.webSocket = webSocket;
        this.openLatch = openLatch;
        this.failure = failure;
        this.textQueue = textQueue;
        this.binaryQueue = binaryQueue;
    }

    public static OkHttpWebSocketClientHarness connect(int port, String path) {
        OkHttpClient client = new OkHttpClient.Builder()//
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(5, TimeUnit.SECONDS)   //
                .writeTimeout(5, TimeUnit.SECONDS)  //
                .build();
        CountDownLatch openLatch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BlockingQueue<String> textQueue = new LinkedBlockingQueue<>();
        BlockingQueue<ByteString> binaryQueue = new LinkedBlockingQueue<>();
        Request request = new Request.Builder().url("ws://127.0.0.1:" + port + path).build();
        WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                textQueue.offer(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                binaryQueue.offer(bytes);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.set(t);
                openLatch.countDown();
            }
        });
        return new OkHttpWebSocketClientHarness(client, webSocket, openLatch, failure, textQueue, binaryQueue);
    }

    public void awaitOpen() throws Exception {
        assertTrue(this.openLatch.await(5, TimeUnit.SECONDS));
        assertNull(this.failure.get());
    }

    public void sendText(String text) {
        assertTrue(this.webSocket.send(text));
    }

    public void sendBinary(String text) {
        assertTrue(this.webSocket.send(ByteString.encodeUtf8(text)));
    }

    public String awaitText() throws Exception {
        String result = this.textQueue.poll(5, TimeUnit.SECONDS);
        assertNull(this.failure.get());
        assertNotNull(result);
        return result;
    }

    public ByteString awaitBinary() throws Exception {
        ByteString result = this.binaryQueue.poll(5, TimeUnit.SECONDS);
        assertNull(this.failure.get());
        assertNotNull(result);
        return result;
    }

    @Override
    public void close() {
        this.webSocket.cancel();
        this.client.dispatcher().executorService().shutdownNow();
        this.client.connectionPool().evictAll();
    }
}