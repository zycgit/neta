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
package net.hasor.neta.http.internal;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.websocket.DefaultWebSocketFrame;
import net.hasor.neta.http.ServletRequest;
import net.hasor.neta.http.WebSocketSession;

/**
 * Default WebSocket session implementation wrapping a neta {@link NetChannel}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultWebSocketSession implements WebSocketSession {
    private final    String              id;
    private final    NetChannel          channel;
    private final    String              requestPath;
    private final    ServletRequest      upgradeRequest;
    private final    Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile boolean             open       = true;

    public DefaultWebSocketSession(NetChannel channel, String requestPath, ServletRequest upgradeRequest) {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.channel = channel;
        this.requestPath = requestPath;
        this.upgradeRequest = upgradeRequest;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public void sendText(String message) throws IOException {
        checkOpen();
        this.channel.sendData(DefaultWebSocketFrame.text(message));
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        checkOpen();
        this.channel.sendData(DefaultWebSocketFrame.binary(data));
    }

    @Override
    public void sendPing() throws IOException {
        checkOpen();
        this.channel.sendData(DefaultWebSocketFrame.ping());
    }

    @Override
    public void sendPong() throws IOException {
        checkOpen();
        this.channel.sendData(DefaultWebSocketFrame.pong());
    }

    @Override
    public void close() throws IOException {
        close(1000, "Normal Closure");
    }

    @Override
    public void close(int statusCode, String reason) throws IOException {
        if (this.open) {
            this.open = false;
            this.channel.sendData(DefaultWebSocketFrame.close(statusCode, reason));
            this.channel.close();
        }
    }

    @Override
    public boolean isOpen() {
        return this.open && !this.channel.isClose();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.channel.getRemoteAddr();
    }

    @Override
    public String getRequestPath() {
        return this.requestPath;
    }

    @Override
    public ServletRequest getUpgradeRequest() {
        return this.upgradeRequest;
    }

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            this.attributes.remove(name);
        } else {
            this.attributes.put(name, value);
        }
    }

    /** Marks this session as closed */
    public void markClosed() {
        this.open = false;
    }

    /** Returns the underlying NetChannel */
    public NetChannel getChannel() {
        return this.channel;
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket session is closed: " + this.id);
        }
    }
}
