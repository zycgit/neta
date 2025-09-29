package net.hasor.neta.channel.virtual;

import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class VrtProvider implements AsyncChannelProvider {
    private static final Logger                              logger = Logger.getLogger(VrtProvider.class);
    public static final  String                              NAME   = "VIRTUAL";
    private final        Map<Integer, VrtAsyncServerChannel> listenPool;

    public VrtProvider(NetManager neta) {
        this.listenPool = new ConcurrentHashMap<>();
    }

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        int listenPort = ((VrtSocketAddress) listenAddr).getAddress();
        if (this.listenPool.containsKey(listenPort)) {
            throw new IOException("VrtListen(" + listenPort + ") already exists.");
        } else {
            Objects.requireNonNull(((VrtSoConfig) soConfig).getRcvConvert(), "rcvConvert is null.");
            VrtAsyncServerChannel serverChannel = new VrtAsyncServerChannel(channelId, this.listenPool, context, listenAddr, soConfig);
            this.listenPool.put(listenPort, serverChannel);
            return serverChannel;
        }
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress targetAddr, SoConfig soConfig) throws IOException {
        VrtSoConfig vrtConfig = (VrtSoConfig) soConfig;
        if (((VrtSocketAddress) targetAddr).isConnectMode()) {
            Objects.requireNonNull(vrtConfig.getRcvConvert(), "rcvConvert is null.");

            if (vrtConfig.getVrtMode() != VrtMode.Default) {
                throw new IllegalArgumentException("VrtMode must be Default");
            }

            int listenPort = ((VrtSocketAddress) targetAddr).getAddress();
            if (!this.listenPool.containsKey(listenPort)) {
                throw new SocketException("Connection refused, VrtListen(" + listenPort + ") is not exist.");
            } else {
                VrtAsyncServerChannel target = this.listenPool.get(listenPort);
                return new VrtAsyncChannel(channelId, target, context, targetAddr, vrtConfig);
            }
        } else {
            return new VrtAsyncChannel(channelId, null, context, targetAddr, vrtConfig);
        }
    }

    @Override
    public void shutdown() {
        if (!this.listenPool.isEmpty()) {
            for (VrtAsyncServerChannel c : this.listenPool.values()) {
                IOUtils.closeQuietly(c);
            }
            logger.info("close vrtListenPool done.");
        }
    }
}
