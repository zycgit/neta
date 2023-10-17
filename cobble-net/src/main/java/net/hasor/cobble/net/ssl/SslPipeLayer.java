package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.net.PipeContext;
import net.hasor.cobble.net.PipeLayer;

import java.io.IOException;

/**
 * 处理 SSL
 */
public class SslPipeLayer implements PipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    @Override
    public void doLayer(PipeContext context, boolean isRcv, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (isRcv) {
            ((SslContextBasic) context.context()).handRcv(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            ((SslContextBasic) context.context()).handSnd(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        }
    }
}