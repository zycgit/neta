package net.hasor.neta.channel;
class ProtoDecoderDuplexWrap<RCV_UP, RCV_DOWN, SND> implements ProtoDuplexer<RCV_UP, RCV_DOWN, SND, SND> {
    private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;

    ProtoDecoderDuplexWrap(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        this.decoder = decoder;
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.decoder.onInit(context);
    }

    @Override
    public void onActive(ProtoContext context, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoSndQueue<SND> sndDown) throws Throwable {
        this.decoder.onActive(context, rcvDown);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onUserEvent(context, event);
        } else {
            return true;
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND> sndUp, ProtoSndQueue<SND> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
            return ProtoStatus.Next;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return ProtoStatus.Next;
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
    }
}