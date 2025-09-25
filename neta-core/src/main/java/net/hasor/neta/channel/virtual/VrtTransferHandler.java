package net.hasor.neta.channel.virtual;
import net.hasor.neta.handler.PlayLoad;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;

public interface VrtTransferHandler {
    void doTransfer(ProtoRcvQueue<PlayLoad> src, ProtoSndQueue<Object> dst);
}