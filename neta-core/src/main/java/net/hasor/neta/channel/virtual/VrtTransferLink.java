package net.hasor.neta.channel.virtual;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.util.List;

class VrtTransferLink {
    private static final Logger               logger = Logger.getLogger(VrtTransferLink.class);
    public final         VrtChannel           target;
    public final         VrtTransferHandler   convert;
    public final         ProtoQueue<PlayLoad> cacheQueue;
    public final         ProtoQueue<Object>   tempQueue;
    protected final      SoContextService     context;

    VrtTransferLink(SoContextService context, VrtChannel target, VrtTransferHandler convert) {
        this.context = context;
        this.target = target;
        this.convert = convert;
        this.cacheQueue = new ProtoQueue<>(-1);
        this.tempQueue = new ProtoQueue<>(-1);
    }

    public void onReceive(int batchSize) {
        if (this.cacheQueue.queueSize() >= batchSize) {
            this.convert.doTransfer(this.cacheQueue, this.tempQueue);
            this.cacheQueue.rcvSubmit();
            this.tempQueue.sndSubmit();
        }

        if (this.tempQueue.hasMore()) {
            int size = this.tempQueue.queueSize();
            try {
                List<Object> objects = this.tempQueue.peekMessage(size);
                this.target.onReceive(objects.toArray());
            } catch (Throwable e) {
                SoException ee = e instanceof SoException ? (SoException) e : new SoRcvException(e.getMessage(), e);
                this.context.notifyRcvChannelException(this.target.getChannelId(), true, ee);
            } finally {
                this.tempQueue.skipMessage(size);
                this.tempQueue.rcvSubmit();
            }
            logger.info("transfer to " + this.target.getChannelId() + ", " + size + " packet onReceive.");
        } else {
            int size = this.cacheQueue.queueSize();
            logger.info("transfer to " + this.target.getChannelId() + ", waiting for batch " + size + "/" + batchSize);
        }
    }
}
