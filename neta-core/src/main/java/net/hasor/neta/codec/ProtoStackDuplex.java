package net.hasor.neta.codec;
import net.hasor.neta.channel.ProtoDuplexer;
import net.hasor.neta.channel.ProtoHandler;

public abstract class ProtoStackDuplex<RCV_UP, RCV_DOWN, SND> implements ProtoDuplexer<RCV_UP, RCV_DOWN, SND, SND> {
    private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;

    ProtoStackDuplex(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        this.decoder = decoder;
    }

}