package net.hasor.cobble.net;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;

class SoConfigUtils {
    public static void configListen(SoConfig config, AsynchronousServerSocketChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, soRcvBuf);
        }
        if (soSndBuf != null) {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, soSndBuf);
        }
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
    }

    public static void configSocket(SoConfig config, AsynchronousSocketChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, soRcvBuf);
        }
        if (soSndBuf != null) {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, soSndBuf);
        }
        if (Boolean.TRUE.equals(config.getSoKeepAlive())) {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        }
    }
}
