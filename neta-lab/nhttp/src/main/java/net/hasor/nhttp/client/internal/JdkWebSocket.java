/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.hasor.nhttp.client.WebSocket;
import net.hasor.nhttp.client.WebSocketListener;
import net.hasor.nhttp.request.Request;

/**
 * WebSocket adapter backed by JDK HttpClient's websocket implementation.
 * @author 赵永春 (zyc@hasor.net)
 */
class JdkWebSocket implements WebSocket {
    private final Request                 request;
    private final java.net.http.WebSocket webSocket;
    private final long                    streamId;
    private final String                  subProtocol;
    private final WebSocketListener       listener;
    private final long                    writeTimeoutMillis;
    private final AtomicBoolean           open            = new AtomicBoolean(true);
    private final AtomicBoolean           closingNotified = new AtomicBoolean(false);
    private final AtomicBoolean           closedNotified  = new AtomicBoolean(false);

    JdkWebSocket(Request request, java.net.http.WebSocket webSocket, long streamId, String subProtocol, WebSocketListener listener, long writeTimeoutMillis) {
        this.request = request;
        this.webSocket = webSocket;
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
        return this.open.get() && !this.webSocket.isInputClosed() && !this.webSocket.isOutputClosed();
    }

    @Override
    public void sendText(String message) throws IOException {
        this.ensureOpen();
        try {
            this.webSocket.sendText(message == null ? "" : message, true).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        this.ensureOpen();
        try {
            this.webSocket.sendBinary(ByteBuffer.wrap(data == null ? new byte[0] : data), true).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
    }

    @Override
    public void sendPing(byte[] data) throws IOException {
        this.ensureOpen();
        try {
            this.webSocket.sendPing(ByteBuffer.wrap(data == null ? new byte[0] : data)).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
    }

    @Override
    public void sendPong(byte[] data) throws IOException {
        this.ensureOpen();
        try {
            this.webSocket.sendPong(ByteBuffer.wrap(data == null ? new byte[0] : data)).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
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
            this.webSocket.sendClose(statusCode, reason == null ? "" : reason).get(this.writeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        } finally {
            this.fireClosed(statusCode, reason);
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
}
