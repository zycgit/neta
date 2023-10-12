package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.net.SoContext;

import javax.net.ssl.SSLEngine;

public class SslHandler {
    private final long       channelID;
    private final SoContext  context;
    private final SSLEngine  sslEngine;
    private final SslBuffers buffers;

    public SslHandler(long channelID, SoContext context, SSLEngine sslEngine, SslBuffers buffers) {
        this.channelID = channelID;
        this.context = context;
        this.sslEngine = sslEngine;
        this.buffers = buffers;
    }

    public void handlerRcv(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        System.out.println();
    }

    public void handlerSnd(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        System.out.println();
    }
}
