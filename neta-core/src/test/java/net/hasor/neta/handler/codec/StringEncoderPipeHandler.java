package net.hasor.neta.handler.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

public class StringEncoderPipeHandler implements PipeHandler<String, ByteBuf> {
    @Override
    public PipeStatus doHandler(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<ByteBuf> dst) {
        String message;
        do {
            message = src.takeMessage();
            if (message != null) {
                byte[] bytes = message.getBytes();
                if (bytes.length > 0) {
                    dst.offerMessage(ByteBufAllocator.DEFAULT.wrap(bytes));
                }
            }
        } while (message != null);
        return PipeStatus.Next;
    }
}