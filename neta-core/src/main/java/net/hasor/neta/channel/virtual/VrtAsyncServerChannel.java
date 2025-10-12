package net.hasor.neta.channel.virtual;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class VrtAsyncServerChannel implements AsyncServerChannel {
    private static final Logger                              logger = Logger.getLogger(VrtAsyncServerChannel.class);
    private final        long                                channelId;
    private final        AtomicBoolean                       closed;
    private final        Map<Integer, VrtAsyncServerChannel> listenPool;
    private final        SoContextService                    context;
    private final        VrtSocketAddress                    listenAddr;
    private final        VrtSoConfig                         soConfig;
    //
    private              VrtListen                           vrtListen;

    public VrtAsyncServerChannel(long channelId, Map<Integer, VrtAsyncServerChannel> listenPool, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.closed = new AtomicBoolean(false);

        this.listenPool = listenPool;
        this.context = (SoContextService) context;
        this.listenAddr = (VrtSocketAddress) listenAddr;
        this.soConfig = (VrtSoConfig) soConfig;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get();
    }

    @Override
    public synchronized NetListen bind(ProtoInitializer initializer) throws IOException {
        if (this.vrtListen != null) {
            throw new IOException("VrtListen(" + this.listenAddr.getAddress() + ") already exists.");
        }

        // create
        VrtTransfer transfer = new VrtTransfer(this.context.getNetManager(), this.soConfig.isAsynchronous());
        transfer.setBatchSize(this.soConfig.getBatchSize());
        transfer.setLossRate(this.soConfig.getLossRate());
        VrtListen listen = new VrtListen(this.channelId, this.listenAddr, this, initializer, this.context, this.soConfig, transfer);

        // init
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

        // create
        VrtChannel serverSite;
        try {
            long channelId = this.context.nextID();
            VrtAsyncChannel vrtAsync = new VrtAsyncChannel(channelId, this, this.context, remoteAddr, this.soConfig);
            ProtoInitializer initializer = this.vrtListen.getInitializer();
            serverSite = new VrtChannel(vrtAsync.getChannelId(), new NetMonitor(), this.vrtListen, VrtMode.Server, initializer, vrtAsync, this.context);
        } catch (Throwable e) {
            throw e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
        }

        // connect transfer
        try {
            this.vrtListen.getTransfer().linkTo(clientSite, serverSite, ((VrtSoConfig) serverSite.getConfig()).getRcvConvert());
            this.vrtListen.getTransfer().linkTo(serverSite, clientSite, ((VrtSoConfig) clientSite.getConfig()).getRcvConvert());

            // init
            this.context.initChannel(serverSite, true);
            return serverSite;
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(serverSite.getChannelId(), true, ee);
            throw ee;
        }
    }

    private void printLog(String msg) {
        if (this.context.getConfig().isPrintLog()) {
            try {
                logger.warn(msg);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("vrtListen(" + this.getChannelId() + ") close.");
        }
        this.vrtListen.getTransfer().close();
        this.listenPool.remove(this.listenAddr.getAddress());
    }
}
