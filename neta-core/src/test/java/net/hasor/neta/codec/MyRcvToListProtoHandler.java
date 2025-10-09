package net.hasor.neta.codec;
import net.hasor.neta.channel.*;

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
