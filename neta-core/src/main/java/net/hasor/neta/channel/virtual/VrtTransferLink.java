package net.hasor.neta.channel.virtual;
import java.util.List;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * Per-target delivery state owned by {@link VrtTransfer}.
 * <p>Each link binds one source-side channel id to one target {@link VrtChannel}.
 * Incoming {@link PlayLoad} objects are first staged in {@link #cacheQueue}, then
 * converted into delivery objects through {@link VrtTransferHandler}, and finally
 * emitted to the target when {@link #onReceive(int)} sees enough queued items.
 * This is where batch delivery and receive-side object conversion actually happen.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
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
                this.target.receiveData(objects.toArray());
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
