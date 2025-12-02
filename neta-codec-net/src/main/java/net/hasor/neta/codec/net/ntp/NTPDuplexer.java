package net.hasor.neta.codec.net.ntp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

public class NTPDuplexer implements ProtoDuplexer<ByteBuf, NTPMessage, NTPMessage, ByteBuf> {
    private final NTPDecoder decoder = new NTPDecoder();
    private final NTPEncoder encoder = new NTPEncoder();

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<NTPMessage> rcvDown, ProtoRcvQueue<NTPMessage> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }
}
