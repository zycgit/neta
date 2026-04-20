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
package net.hasor.nhttp.client.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.websocket.PingWebSocketEvent;
import net.hasor.neta.codec.http.websocket.PongWebSocketEvent;
import net.hasor.neta.codec.http.websocket.WebSocketUtils;
import net.hasor.nhttp.client.WebSocket;
import net.hasor.nhttp.client.WebSocketListener;
import net.hasor.nhttp.request.Request;

/**
 * Default WebSocket implementation used by the client runtime.
 * @author 赵永春 (zyc@hasor.net)
 */
public class RealWebSocket implements WebSocket {
    private final Request           request;
    private final NetChannel        channel;
    private final long              streamId;
    private final String            subProtocol;
    private final WebSocketListener listener;
    private final long              writeTimeoutMillis;
    private final AtomicBoolean     open            = new AtomicBoolean(true);
    private final AtomicBoolean     closingNotified = new AtomicBoolean(false);
    private final AtomicBoolean     closedNotified  = new AtomicBoolean(false);

    public RealWebSocket(Request request, NetChannel channel, long streamId, String subProtocol, WebSocketListener listener, long writeTimeoutMillis) {
        this.request = request;
        this.channel = channel;
        this.streamId = streamId;
        this.subProtocol = subProtocol;
        this.listener = listener;
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    @Override
    public Request request() {
        return this.request;
    }

    @Override
    public long streamId() {
        return this.streamId;
    }

    @Override
    public String subProtocol() {
        return this.subProtocol;
    }

    @Override
    public boolean isOpen() {
        return this.open.get() && !this.channel.isClose();
    }

    @Override
    public void sendText(String message) throws IOException {
        this.ensureOpen();
        byte[] bytes = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        this.send(WebSocketUtils.textMessage(ByteBuf.wrap(bytes)).streamId(this.streamId));
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        this.ensureOpen();
        this.send(WebSocketUtils.binaryMessage(ByteBuf.wrap(data == null ? new byte[0] : data)).streamId(this.streamId));
    }

    @Override
    public void sendPing(byte[] data) {
        if (!this.isOpen()) {
            return;
        }
        PingWebSocketEvent event = WebSocketUtils.pingEvent(ByteBuf.wrap(data == null ? new byte[0] : data));
        event.streamId(this.streamId);
        this.channel.fireEvent(PingWebSocketEvent.class, event);
    }

    @Override
    public void sendPong(byte[] data) {
        if (!this.isOpen()) {
            return;
        }
        PongWebSocketEvent event = WebSocketUtils.pongEvent(ByteBuf.wrap(data == null ? new byte[0] : data));
        event.streamId(this.streamId);
        this.channel.fireEvent(PongWebSocketEvent.class, event);
    }

    @Override
    public void close() throws IOException {
        this.close(1000, "normal closure");
    }

    @Override
    public void close(int statusCode, String reason) throws IOException {
        if (this.open.compareAndSet(true, false)) {
            this.fireClosing(statusCode, reason);
        }
        try {
            this.send(WebSocketUtils.closeFrame(statusCode, reason).streamId(this.streamId));
        } finally {
            try {
                this.channel.close().get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                throw ClientRuntime.asIOException(e);
            } finally {
                this.fireClosed(statusCode, reason);
            }
        }
    }

    void markClosed() {
        this.open.set(false);
    }

    void fireOpen() {
        try {
            this.listener.onOpen(this);
        } catch (Throwable ignored) {
        }
    }

    void fireText(String message) {
        try {
            this.listener.onText(this, message);
        } catch (Throwable ignored) {
        }
    }

    void fireBinary(byte[] data) {
        try {
            this.listener.onBinary(this, data);
        } catch (Throwable ignored) {
        }
    }

    void firePing(byte[] data) {
        try {
            this.listener.onPing(this, data);
        } catch (Throwable ignored) {
        }
    }

    void firePong(byte[] data) {
        try {
            this.listener.onPong(this, data);
        } catch (Throwable ignored) {
        }
    }

    void fireClosing(int statusCode, String reason) {
        if (this.closingNotified.compareAndSet(false, true)) {
            try {
                this.listener.onClosing(this, statusCode, reason);
            } catch (Throwable ignored) {
            }
        }
    }

    void fireClosed(int statusCode, String reason) {
        this.open.set(false);
        if (this.closedNotified.compareAndSet(false, true)) {
            try {
                this.listener.onClosed(this, statusCode, reason);
            } catch (Throwable ignored) {
            }
        }
    }

    void fireFailure(Throwable error) {
        try {
            this.listener.onFailure(this, error);
        } catch (Throwable ignored) {
        }
    }

    private void ensureOpen() throws IOException {
        if (!this.isOpen()) {
            throw new IOException("websocket is closed");
        }
    }

    private void send(Object message) throws IOException {
        try {
            this.channel.sendData(message).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
    }
}