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
package net.hasor.neta.channel;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.quic.QuicProvider;
import net.hasor.neta.channel.sctp.SctpProvider;
import net.hasor.neta.channel.tcp.TcpProvider;
import net.hasor.neta.channel.udp.UdpProvider;
import net.hasor.neta.channel.virtual.VrtProvider;

/**
 * Entry point for Neta's AIO network layer.
 * Manages server listeners and client connections over TCP, UDP, QUIC, SCTP and virtual transports.
 * <pre>
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │                        NetManager                           │
 *  │   bind(addr, initializer)          connect(addr, init)      │
 *  │          │                                 │                │
 *  │    ┌─────▼──────┐                  ┌───────▼───────┐        │
 *  │    │  NetListen │  ──onAccept──►   │   NetChannel  │        │
 *  │    └────────────┘                  └───────┬───────┘        │
 *  │                                            │                │
 *  │                              ┌─────────────▼────────────┐   │
 *  │                              │     Protocol Stack       │   │
 *  │                              │  [codec] → [handler] → … │   │
 *  │                              └──────────────────────────┘   │
 *  └─────────────────────────────────────────────────────────────┘
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetManager extends AbstractNetManager {
    private static final Logger                            logger = Logger.getLogger(NetManager.class);
    protected final      Map<String, AsyncChannelProvider> providerMap;

    public NetManager() {
        this(new NetConfig());
    }

    public NetManager(NetConfig config) {
        super(config);
        this.providerMap = new ConcurrentHashMap<>();
    }

    protected AsyncChannelProvider findProvider(String protocol) throws IOException {
        AsyncChannelProvider existing = this.providerMap.get(protocol);
        if (existing != null) {
            return existing;
        }

        AsyncChannelProvider provider;
        if (StringUtils.equalsIgnoreCase(TcpProvider.NAME, protocol)) {
            provider = new TcpProvider(this);
        } else if (StringUtils.equalsIgnoreCase(UdpProvider.NAME, protocol)) {
            provider = new UdpProvider(this);
        } else if (StringUtils.equalsIgnoreCase(VrtProvider.NAME, protocol)) {
            provider = new VrtProvider(this);
        } else if (StringUtils.equalsIgnoreCase(SctpProvider.NAME, protocol)) {
            provider = new SctpProvider(this);
        } else if (StringUtils.equalsIgnoreCase(QuicProvider.NAME, protocol)) {
            provider = new QuicProvider(this);
        } else {
            throw new UnsupportedOperationException("not support protocol : " + protocol);
        }

        AsyncChannelProvider prev = this.providerMap.putIfAbsent(protocol, provider);
        if (prev != null) {
            return prev;
        }
        return provider;
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     * @param listenAddr local address:port for listenAddr
     * @param initializer Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen bind(SocketAddress listenAddr, ProtoInitializer initializer, SoConfig soConfig) throws IOException {
        long channelID = this.context.nextID();
        AsyncChannelProvider provider = this.findProvider(soConfig.getProtocol());
        AsyncServerChannel socket = provider.createServerChannel(channelID, this.context, listenAddr, soConfig);
        NetListen listen = socket.bind(initializer);
        logger.info("listen at " + listenAddr);
        return listen;
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param initializer Application layer network protocol
     */
    public NetChannel connectSync(SocketAddress remoteAddr, ProtoInitializer initializer, SoConfig soConfig) throws IOException {
        try {
            Future<NetChannel> future = this.connectAsync(remoteAddr, initializer, soConfig);
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException(cause);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param initializer Application layer network protocol
     */
    public Future<NetChannel> connectAsync(SocketAddress remoteAddr, ProtoInitializer initializer, SoConfig soConfig) {
        Future<NetChannel> future = new BasicFuture<>();
        AsyncChannel asyncChannel = null;

        try {
            long channelID = this.context.nextID();
            AsyncChannelProvider provider = this.findProvider(soConfig.getProtocol());
            asyncChannel = provider.createClientChannel(channelID, this.context, remoteAddr, soConfig);
            asyncChannel.connectTo(initializer, future);
            return future;
        } catch (Throwable e) {
            IOUtils.closeQuietly(asyncChannel);
            future.failed(e);
            return future;
        }
    }

    /** Returns the channel identified by {@code channelId}, or {@code null} if not found. */
    public SoChannel<?> findChannel(long channelId) {
        return this.context.findChannel(channelId);
    }

    /** Returns the first active {@link NetListen} bound to {@code port}, or {@code null} if none. */
    public NetListen findListen(int port) {
        AtomicReference<NetListen> found = new AtomicReference<>();
        this.context.foreachListen(netListen -> {
            if (netListen.getListenPort() == port) {
                found.set(netListen);
            }
        });

        return found.get();
    }

    @Override
    protected void shutdown0(boolean now) {
        // close all channel
        if (now) {
            logger.info("close all channel for now.");
        } else {
            logger.info("close all channel.");
        }
        this.context.closeAll(now);

        // waiting close
        for (AsyncChannelProvider provider : this.providerMap.values()) {
            provider.shutdown();
        }

        this.context.shutdown();
    }
}
