package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;

public interface VrtTransferHandler {
    void doTransfer(ProtoRcvQueue<PlayLoad> src, ProtoSndQueue<Object> dst);
}