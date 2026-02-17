package net.hasor.neta.codec;
import java.util.List;
import net.hasor.neta.channel.*;

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
