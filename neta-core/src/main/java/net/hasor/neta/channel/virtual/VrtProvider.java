package net.hasor.neta.channel.virtual;

import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class VrtProvider implements AsyncChannelProvider {
    public static final String NAME = "VIRTUAL";

    private static final Map<Integer, VrtAsyncServerChannel> VRT_LISTEN_POOL;

    static {
        VRT_LISTEN_POOL = new ConcurrentHashMap<>();
    }

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        int listenPort = ((VrtSocketAddress) listenAddr).getAddress();
        if (VRT_LISTEN_POOL.containsKey(listenPort)) {
            throw new IOException("VrtListen(" + listenPort + ") already exists.");
        } else {
            Objects.requireNonNull(((VrtSoConfig) soConfig).getRcvConvert(), "rcvConvert is null.");
            synchronized (VRT_LISTEN_POOL) {
                VrtAsyncServerChannel serverChannel = new VrtAsyncServerChannel(channelId, VRT_LISTEN_POOL, context, listenAddr, soConfig);
                VRT_LISTEN_POOL.put(listenPort, serverChannel);
                return serverChannel;
            }
        }
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress targetAddr, SoConfig soConfig) throws IOException {
        VrtSoConfig vrtConfig = (VrtSoConfig) soConfig;
        if (((VrtSocketAddress) targetAddr).isConnectMode()) {
            Objects.requireNonNull(vrtConfig.getRcvConvert(), "rcvConvert is null.");

            if (vrtConfig.getVrtMode() != VrtMode.Default) {
                throw new IllegalArgumentException("VrtMode must be Default");
            }

            int listenPort = ((VrtSocketAddress) targetAddr).getAddress();
            if (!VRT_LISTEN_POOL.containsKey(listenPort)) {
                throw new SocketException("Connection refused, VrtListen(" + listenPort + ") is not exist.");
            } else {
                VrtAsyncServerChannel target = VRT_LISTEN_POOL.get(listenPort);
                return new VrtAsyncChannel(channelId, target, context, targetAddr, vrtConfig);
            }
        } else {
            return new VrtAsyncChannel(channelId, null, context, targetAddr, vrtConfig);
        }
    }
}
