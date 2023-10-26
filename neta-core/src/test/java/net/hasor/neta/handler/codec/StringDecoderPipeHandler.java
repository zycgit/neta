package net.hasor.neta.handler.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

import java.io.IOException;

public class StringDecoderPipeHandler implements PipeHandler<ByteBuf, String> {
    @Override
    public PipeStatus doHandler(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) throws IOException {
        ByteBuf byteBuf = src.takeMessage();
        if (byteBuf == null) {
            return PipeStatus.Next;
        }
        String line;
        do {
            line = byteBuf.readLine();
            if (line != null) {
                dst.offerMessage(line);
            }
        } while (line != null && dst.hasSlot());

        byteBuf.markReader();
        return PipeStatus.Next;
    }
}