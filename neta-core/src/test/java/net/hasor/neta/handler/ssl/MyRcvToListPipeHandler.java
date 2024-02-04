package net.hasor.neta.handler.ssl;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

import java.util.List;

public class MyRcvToListPipeHandler implements PipeHandler<String, String> {

    private final List<String> rcvMessage;

    public MyRcvToListPipeHandler(List<String> rcvMessage) {
        this.rcvMessage = rcvMessage;
    }

    @Override
    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
        while (src.hasMore()) {
            this.rcvMessage.add(src.takeMessage());
        }
        return PipeStatus.Next;
    }
}
