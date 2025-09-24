package net.hasor.neta.channel.virtual;
import java.util.function.Function;

class VrtTransferLink {
    public final VrtChannel     target;
    public final Function<?, ?> convert;

    VrtTransferLink(VrtChannel target, Function<?, ?> convert) {
        this.target = target;
        this.convert = convert;
    }
}
