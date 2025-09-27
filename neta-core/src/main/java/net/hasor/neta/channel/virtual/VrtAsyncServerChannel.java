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
    private              VrtListen                           vrtListen;
    private final        VrtTransfer                         transfer;
    private final        AtomicBoolean                       closed;
    //
    private final        Map<Integer, VrtAsyncServerChannel> listenPool;
    private final        SoContextService                    context;
    private final        VrtSocketAddress                    listenAddr;
    private final        SoConfig                            soConfig;

    public VrtAsyncServerChannel(long channelId, Map<Integer, VrtAsyncServerChannel> listenPool, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.transfer = new VrtTransfer(context.getNetManager());
        this.closed = new AtomicBoolean(false);

        this.listenPool = listenPool;
        this.context = (SoContextService) context;
        this.listenAddr = (VrtSocketAddress) listenAddr;
        this.soConfig = soConfig;
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
    public synchronized NetListen bind(ProtoInitializer initializer) throws Throwable {
        if (this.vrtListen != null) {
            throw new IOException("VrtListen(" + this.listenAddr.getAddress() + ") already exists.");
        }

        // create
        VrtListen listen = new VrtListen(this.channelId, this.listenAddr, this, initializer, this.context, this.soConfig);

        // init
        this.context.initChannel(listen, false);
        this.vrtListen = listen;
        return vrtListen;
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
        long channelId = this.context.nextID();
        VrtAsyncChannel vrtAsync = new VrtAsyncChannel(channelId, this, this.context, remoteAddr, this.soConfig);
        ProtoInitializer initializer = this.vrtListen.getInitializer();
        VrtChannel serverSite = new VrtChannel(vrtAsync.getChannelId(), new NetMonitor(), this.vrtListen, VrtMode.Server, initializer, vrtAsync, this.context);

        // connect transfer
        this.transfer.linkTo(clientSite, serverSite, ((VrtSoConfig) serverSite.getConfig()).getRcvConvert());
        this.transfer.linkTo(serverSite, clientSite, ((VrtSoConfig) clientSite.getConfig()).getRcvConvert());

        // init
        this.context.initChannel(serverSite, true);
        return serverSite;
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
        this.transfer.close();
        this.listenPool.remove(this.listenAddr.getAddress());
    }
}
