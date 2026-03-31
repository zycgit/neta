package net.hasor.neta.channel.virtual;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * Provider for virtual channels.
 * <p>This provider maintains the process-local listener registry indexed by virtual port.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class VrtProvider implements AsyncChannelProvider {
    public static final  String                              NAME   = "VIRTUAL";
    private static final Logger                              logger = Logger.getLogger(VrtProvider.class);
    private final        Map<Integer, VrtAsyncServerChannel> listenPool;

    /**
     * Create the virtual transport provider.
     * @param neta the current NetManager
     */
    public VrtProvider(NetManager neta) {
        this.listenPool = new ConcurrentHashMap<>();
    }

    /**
     * Create a virtual server channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     * @return the server channel
     * @throws IOException if an I/O error occurs during creation
     */
    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        int listenPort = ((VrtSocketAddress) listenAddr).getAddress();
        if (this.listenPool.containsKey(listenPort)) {
            throw new SocketException("VrtListen(" + listenPort + ") already exists.");
        } else {
            Objects.requireNonNull(((VrtSoConfig) soConfig).getRcvConvert(), "rcvConvert is null.");
            VrtAsyncServerChannel serverChannel = new VrtAsyncServerChannel(channelId, this.listenPool, context, listenAddr, soConfig);
            this.listenPool.put(listenPort, serverChannel);
            return serverChannel;
        }
    }

    /**
     * Create a virtual client channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param targetAddr the target address
     * @param soConfig the channel configuration
     * @return the client channel
     * @throws IOException if an I/O error occurs during creation
     */
    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress targetAddr, SoConfig soConfig) throws IOException {
        VrtSoConfig vrtConfig = (VrtSoConfig) soConfig;
        if (((VrtSocketAddress) targetAddr).isConnectMode()) {
            Objects.requireNonNull(vrtConfig.getRcvConvert(), "rcvConvert is null.");

            if (vrtConfig.getVrtMode() != VrtMode.Default) {
                throw new SocketException("VrtMode must be Default");
            }

            int listenPort = ((VrtSocketAddress) targetAddr).getAddress();
            VrtAsyncServerChannel target = this.listenPool.get(listenPort);
            if (target == null) {
                throw new SocketException("Connection refused, VrtListen(" + listenPort + ") is not exist.");
            } else {
                return new VrtAsyncChannel(channelId, target, context, targetAddr, vrtConfig);
            }
        } else {
            return new VrtAsyncChannel(channelId, null, context, targetAddr, vrtConfig);
        }
    }

    /**
     * Close all virtual listeners owned by this provider.
     */
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
