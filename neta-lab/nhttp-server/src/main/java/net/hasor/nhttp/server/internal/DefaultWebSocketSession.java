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
package net.hasor.nhttp.server.internal;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.hasor.neta.channel.DefaultSoTask;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.websocket.WebSocketUtils;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.WebSocketSession;

/**
 * Default WebSocket session implementation wrapping a neta {@link NetChannel}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultWebSocketSession implements WebSocketSession {
    private final String                             id;
    private final ProtoContext                       context;
    private final NetChannel                         channel;
    private final long                               streamId;
    private final String                             requestPath;
    private final ServletRequest                     upgradeRequest;
    private final Map<String, Object>                attributes     = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingSend> pendingSends   = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean                      drainScheduled = new AtomicBoolean(false);
    private volatile boolean                         open           = true;

    private static class PendingSend {
        private final HttpObject frame;
        private final boolean    closeAfterSend;

        private PendingSend(HttpObject frame, boolean closeAfterSend) {
            this.frame = frame;
            this.closeAfterSend = closeAfterSend;
        }
    }

    public DefaultWebSocketSession(ProtoContext context, NetChannel channel, long streamId, String requestPath, ServletRequest upgradeRequest) {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.context = context;
        this.channel = channel;
        this.streamId = streamId;
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
        this.sendFrame(WebSocketUtils.textFrame(message).streamId(this.streamId), false);
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        checkOpen();
        this.sendFrame(WebSocketUtils.binaryFrame(data).streamId(this.streamId), false);
    }

    @Override
    public void sendPing() throws IOException {
        checkOpen();
        this.sendFrame(WebSocketUtils.pingFrame().streamId(this.streamId), false);
    }

    @Override
    public void sendPong() throws IOException {
        checkOpen();
        this.sendFrame(WebSocketUtils.pongFrame().streamId(this.streamId), false);
    }

    @Override
    public void close() throws IOException {
        close(1000, "Normal Closure");
    }

    @Override
    public void close(int statusCode, String reason) throws IOException {
        if (this.open) {
            this.open = false;
            this.sendFrame(WebSocketUtils.closeFrame(statusCode, reason).streamId(this.streamId), true);
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

    private void sendFrame(HttpObject frame, boolean closeAfterSend) throws IOException {
        if (NetChannel.isCurrentThreadInPipeline(this.channel) && this.context.getSoContext() instanceof SoContextService) {
            this.pendingSends.offer(new PendingSend(frame, closeAfterSend));
            this.scheduleDrain((SoContextService) this.context.getSoContext());
            return;
        }

        this.channel.sendData(frame).onFinal(future -> {
            if (closeAfterSend) {
                this.channel.close();
            }
        });
    }

    private void scheduleDrain(SoContextService soContext) {
        if (!this.drainScheduled.compareAndSet(false, true)) {
            return;
        }
        soContext.submitSoTask(new DefaultSoTask() {
            @Override
            protected void doWork(int retryCnt) {
                PendingSend pending;
                while ((pending = pendingSends.poll()) != null) {
                    PendingSend current = pending;
                    channel.sendData(current.frame).onFinal(future -> {
                        if (current.closeAfterSend) {
                            channel.close();
                        }
                    });
                    if (current.closeAfterSend) {
                        break;
                    }
                }
                drainScheduled.set(false);
                if (!pendingSends.isEmpty()) {
                    scheduleDrain(soContext);
                }
                finishTask();
            }
        }, null);
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket session is closed: " + this.id);
        }
    }
}
