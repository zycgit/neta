package net.hasor.neta.channel.virtual;

import net.hasor.cobble.ObjectUtils;

import java.util.Objects;

public class VrtTransfer {
    private final VrtChannel source;
    private final VrtChannel target;
    private       boolean    closed;

    VrtTransfer(VrtChannel source, VrtChannel target) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.closed = false;
        ObjectUtils.assertTrue(this.source.isServer(), "source must be server.");
        ObjectUtils.assertTrue(this.target.isClient(), "target must be client.");

        this.source.onClose(c -> this.close((VrtChannel) c));
        this.target.onClose(c -> this.close((VrtChannel) c));
    }

    public void close() {
        this.close(null);
    }

    private void close(VrtChannel event) {
        //        this.source.transferFree();
        //        this.target.transferFree();
    }

    //    public void transferTo(VrtChannel channel) throws SoCloseException {
    //        if (this.isClose()) {
    //            throw SoCloseException.INSTANCE;
    //        }
    //
    //        if (this.transfer != null || channel.transfer != null) {
    //            throw new IllegalStateException("VrtChannel has been transferred.");
    //        }
    //
    //        VrtTransfer vt = new VrtTransfer(this, channel);
    //        this.transfer = vt;
    //        channel.transfer = vt;
    //    }
}
