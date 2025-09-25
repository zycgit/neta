package net.hasor.neta.channel.virtual;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.handler.PlayLoad;
import net.hasor.neta.handler.ProtoQueue;

import java.util.List;

class VrtTransferLink {
    private static final Logger               logger = Logger.getLogger(VrtTransferLink.class);
    public final         VrtChannel           target;
    public final         VrtTransferHandler   convert;
    public final         ProtoQueue<PlayLoad> cacheQueue;
    public final         ProtoQueue<Object>   tempQueue;

    VrtTransferLink(VrtChannel target, VrtTransferHandler convert) {
        this.target = target;
        this.convert = convert;
        this.cacheQueue = new ProtoQueue<>(-1);
        this.tempQueue = new ProtoQueue<>(-1);
    }

    public void onReceive(boolean asynchronous, int batchSize) {
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
