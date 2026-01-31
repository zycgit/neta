package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;

/**
 * Handler interface for virtual data transfer.
 * Defines how data is converted or moved between queues in a virtual link.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface VrtTransferHandler {
    void doTransfer(ProtoRcvQueue<PlayLoad> src, ProtoSndQueue<Object> dst);
}