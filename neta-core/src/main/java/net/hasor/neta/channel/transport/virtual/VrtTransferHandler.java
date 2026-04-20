package net.hasor.neta.channel.transport.virtual;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Handler interface used by virtual data transfer.
 * <p>This interface defines how data is converted or moved between queues inside a virtual link.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface VrtTransferHandler {
    /**
     * Convert payloads from the source receive queue and write them into the target send queue.
     * @param src the source receive queue
     * @param dst the target send queue
     */
    void doTransfer(ProtoRcvQueue<PlayLoad> src, ProtoSndQueue<Object> dst);
}