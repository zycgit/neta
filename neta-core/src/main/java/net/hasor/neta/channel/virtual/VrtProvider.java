package net.hasor.neta.channel.virtual;

import net.hasor.neta.channel.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;

public class VrtProvider implements AsyncChannelProvider {
    public static final String NAME = "VIRTUAL";

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        throw new UnsupportedEncodingException("VrtChannel not support");
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress vrtAddress, SoConfig soConfig) throws IOException {
        return new VrtAsyncChannel(channelId, context, vrtAddress, soConfig);
    }
}
