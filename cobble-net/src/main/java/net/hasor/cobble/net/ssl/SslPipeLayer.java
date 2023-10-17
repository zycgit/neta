package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.net.*;

import java.io.IOException;

/**
 * SSL 网络协议层
 */
public class SslPipeLayer implements PipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    @Override
    public void initLayer(PipeContext pipeContext) throws Exception {
        NetChannel channel = pipeContext.channel();

        long channelID = channel.getChannelID();
        SoResManager rm = pipeContext.getSoResManager();
        boolean clientMode = channel.isClient();

        SoContext context = pipeContext.context(SoContext.class);
        SslConfig sslConfig = context.getConfig().getSslConfig();
        pipeContext.context(SslContext.class, new JdkSslContext(channelID, context, sslConfig, rm, clientMode));
    }

    @Override
    public PipeStatus doLayer(PipeContext context, boolean isRcv, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (isRcv) {
            ((SslContextBasic) context.context(SslContext.class)).handRcv(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            ((SslContextBasic) context.context(SslContext.class)).handSnd(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        }
        return PipeStatus.Success;
    }

    @Override
    public void releaseLayer(PipeContext pipeContext) {

    }
}