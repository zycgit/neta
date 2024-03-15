package net.hasor.neta.handler.ssl;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.util.List;

public class MyRcvToListProtoHandler implements ProtoHandler<String, String> {

    private final List<String> rcvMessage;

    public MyRcvToListProtoHandler(List<String> rcvMessage) {
        this.rcvMessage = rcvMessage;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
        while (src.hasMore()) {
            this.rcvMessage.add(src.takeMessage());
        }
        return ProtoStatus.Next;
    }
}
