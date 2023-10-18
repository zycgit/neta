package net.hasor.cobble.net.handler.ssl;
import net.hasor.cobble.net.bytebuf.ByteBuf;
import net.hasor.cobble.net.channel.*;

import java.io.IOException;
import java.util.Objects;

/**
 * SSL 网络协议层
 */
public class SslPipeLayer implements PipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    private final SslConfig config;

    public SslPipeLayer(SslConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public void initLayer(PipeContext pipeContext) throws Exception {
        NetChannel channel = pipeContext.channel();

        long channelID = channel.getChannelID();
        SoResManager rm = pipeContext.getSoResManager();
        boolean clientMode = channel.isClient();

        SoContext context = pipeContext.context(SoContext.class);
        pipeContext.context(SslContext.class, new JdkSslContext(channelID, context, this.config, rm, clientMode));
    }

    @Override
    public PipeStatus doLayer(PipeContext context, boolean isRcv, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (isRcv) {
            ((SslContextBasic) context.context(SslContext.class)).handRcv(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            ((SslContextBasic) context.context(SslContext.class)).handSnd(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        }
        return PipeStatus.Finish;
    }

    @Override
    public void releaseLayer(PipeContext pipeContext) {

    }
}