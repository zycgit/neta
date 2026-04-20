package net.hasor.neta.channel.transport.virtual;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
/**
 * In-process server-side entry point for the virtual transport.
 * <p>This class maintains listener registration in the provider-shared {@code listenPool}, owns the
 * server-side {@link VrtTransfer}, and lazily creates a server-side {@link VrtChannel} when a
 * client resolves this listener through
 * {@link AsyncChannel#connectTo(ProtoInitializer, net.hasor.cobble.concurrent.future.Future)}.
 * <p><b>Lifecycle:</b>
 * <pre>
 *   AsyncChannelProvider.createServerChannel(...)
 *       -> register this instance by virtual port
 *   {@link AsyncServerChannel#bind(ProtoInitializer)}
 *       -> create VrtTransfer + VrtListen
 *       -> context.initChannel(listen, initializer)
 *   client.connectTo(...)
 *       -> acceptLink(clientSide)
 *       -> create a server-side VrtChannel
 *       -> establish bidirectional VrtTransfer links
 *   close()
 *       -> close listen/transfer and remove the port from listenPool
 * </pre>
 * <p><b>Listen pool:</b> the shared map is indexed only by the numeric address in
 * {@link VrtSocketAddress}, so each virtual port can have only one listener.
 * <p><b>Bind contract:</b> {@link #bind(ProtoInitializer)} can succeed only once because a listener
 * can be initialized only once, and port reuse is not supported.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see VrtAsyncChannel
 * @see VrtListen
 * @see VrtSoConfig
 */
public class VrtAsyncServerChannel implements AsyncServerChannel {
    private static final Logger                       logger = Logger.getLogger(VrtAsyncServerChannel.class);
    private final long                                channelId;
    private final AtomicBoolean                       closed;
    private final Map<Integer, VrtAsyncServerChannel> listenPool;
    private final SoContextService                    context;
    private final VrtSocketAddress                    listenAddr;
    private final VrtSoConfig                         soConfig;
    //
    private VrtListen vrtListen;

    /**
     * Create a virtual server asynchronous channel.
     * @param channelId the channel ID
     * @param listenPool the listener pool
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     */
    public VrtAsyncServerChannel(long channelId, Map<Integer, VrtAsyncServerChannel> listenPool, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.closed = new AtomicBoolean(false);

        this.listenPool = listenPool;
        this.context = (SoContextService) context;
        this.listenAddr = (VrtSocketAddress) listenAddr;
        this.soConfig = (VrtSoConfig) soConfig;
    }

    /**
     * Return the current server channel ID.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the configuration used by the current server.
     * @return the configuration object
     */
    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Determine whether the current server channel is still open.
     * @return true if it is open
     */
    @Override
    public boolean isOpen() {
        return !this.closed.get();
    }

    /**
     * Bind the current virtual listen address and initialize the listen channel.
     * @param initializer the protocol initializer
     * @return the listen handle
     * @throws IOException if an I/O error occurs during bind or initialization
     */
    @Override
    public synchronized NetListen bind(ProtoInitializer initializer) throws IOException {
        if (this.vrtListen != null) {
            throw new IOException("VrtListen(" + this.listenAddr.getAddress() + ") already exists.");
        }

        // Create the listen object.
        VrtTransfer transfer = new VrtTransfer(this.context.getNetManager(), this.soConfig.isAsynchronous());
        transfer.setBatchSize(this.soConfig.getBatchSize());
        transfer.setLossRate(this.soConfig.getLossRate());
        VrtListen listen = new VrtListen(this.channelId, this.listenAddr, this, initializer, this.context, this.soConfig, transfer);

        // Initialize the listen channel.
        try {
            this.context.initChannel(listen, false);
            this.vrtListen = listen;
            return vrtListen;
        } catch (Throwable e) {
            logger.error("ERROR: bindFailed, " + e.getMessage(), e);
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }
    }

    /**
     * Accept a connection from a client-side virtual channel and create the corresponding server channel.
     * @param clientSite the client-side virtual channel
     * @return the newly created server-side channel
     * @throws Throwable if an error occurs during setup
     */
    VrtChannel acceptLink(VrtChannel clientSite) throws Throwable {
        if (this.vrtListen.isSuspend()) {
            throw new SocketException("ERROR: AcceptFailed, listen is suspend.");
        }

        SocketAddress remoteAddr = clientSite.getLocalAddr();
        if (!this.context.acceptChannel(remoteAddr)) {
            throw new SocketException("reject(" + this.vrtListen.getChannelId() + ") R:" + remoteAddr + " -> L:" + this.listenAddr);
        } else {
            printLog("accept(" + this.vrtListen.getChannelId() + ") R:" + remoteAddr + " -> L:" + this.listenAddr);
        }

        // Create the server-side channel.
        VrtChannel serverSite;
        try {
            long channelId = this.context.nextID();
            VrtAsyncChannel vrtAsync = new VrtAsyncChannel(channelId, this, this.context, remoteAddr, this.soConfig);
            ProtoInitializer initializer = this.vrtListen.getInitializer();
            serverSite = new VrtChannel(vrtAsync.getChannelId(), new NetMonitor(), this.vrtListen, VrtMode.Server, initializer, vrtAsync, this.context);
        } catch (Throwable e) {
            throw e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
        }

        // Establish transport links.
        try {
            this.vrtListen.getTransfer().linkTo(clientSite, serverSite, ((VrtSoConfig) serverSite.getConfig()).getRcvConvert());
            this.vrtListen.getTransfer().linkTo(serverSite, clientSite, ((VrtSoConfig) clientSite.getConfig()).getRcvConvert());

            // Initialize the server-side channel.
            this.context.initChannel(serverSite, true);
            return serverSite;
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(serverSite.getChannelId(), true, ee);
            throw ee;
        }
    }

    /**
     * Print a warning log when logging is enabled.
     * @param msg the log message
     */
    private void printLog(String msg) {
        if (this.context.getConfig().isPrintLog()) {
            try {
                logger.warn(msg);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Close the current server channel and remove its registration from the listener pool.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        if (this.context.getConfig().isPrintLog()) {
            logger.info("vrtListen(" + this.getChannelId() + ") close.");
        }
        if (this.vrtListen != null) {
            this.vrtListen.getTransfer().close();
        }
        this.listenPool.remove(this.listenAddr.getAddress());
    }
}
