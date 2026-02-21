package net.hasor.neta.channel;
class ProtoEncoderDuplexWrap<RCV, SND_UP, SND_DOWN> implements ProtoDuplexer<RCV, RCV, SND_UP, SND_DOWN> {
    private final ProtoHandler<SND_UP, SND_DOWN> encoder;

    ProtoEncoderDuplexWrap(ProtoHandler<SND_UP, SND_DOWN> encoder) {
        this.encoder = encoder;
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.encoder.onInit(context);
    }

    @Override
    public void onActive(ProtoContext context, ProtoSndQueue<RCV> rcvDown, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
        this.encoder.onActive(context, sndDown);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return true;
        } else {
            return this.encoder.onUserEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV> rcvUp, ProtoSndQueue<RCV> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
        if (isRcv) {
            rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
            return ProtoStatus.Next;
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return ProtoStatus.Next;
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.encoder.onClose(context);
    }
}