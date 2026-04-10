/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.transport.tcp;
import java.io.IOException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.NetworkChannel;
import java.util.Set;
import net.hasor.cobble.logging.Logger;

/**
 * Helper utility that applies settings from {@link TcpSoConfig} to
 * {@link java.nio.channels.AsynchronousSocketChannel} and
 * {@link java.nio.channels.AsynchronousServerSocketChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see TcpSoConfig
 */
class TcpSoConfigUtils {
    private static final Logger                logger       = Logger.getLogger(TcpSoConfigUtils.class);
    private static final SocketOption<Integer> SO_SNDBUF    = StandardSocketOptions.SO_SNDBUF;
    private static final SocketOption<Integer> SO_RCVBUF    = StandardSocketOptions.SO_RCVBUF;
    private static final SocketOption<Boolean> SO_REUSEADDR = StandardSocketOptions.SO_REUSEADDR;
    private static final SocketOption<Boolean> SO_KEEPALIVE = StandardSocketOptions.SO_KEEPALIVE;
    private static final SocketOption<Integer> TCP_KEEPIDLE;
    private static final SocketOption<Integer> TCP_KEEPINTERVAL;
    private static final SocketOption<Integer> TCP_KEEPCOUNT;

    static {
        SocketOption<Integer> tcpKeepIdleTmp = null;
        SocketOption<Integer> tcpKeepIntervalTmp = null;
        SocketOption<Integer> tcpKeepCountTmp = null;

        try (AsynchronousSocketChannel c = AsynchronousSocketChannel.open()) {
            Set<SocketOption<?>> options = c.supportedOptions();
            for (SocketOption<?> opt : options) {
                if (opt.name().equals("TCP_KEEPIDLE")) {
                    tcpKeepIdleTmp = (SocketOption<Integer>) opt;
                } else if (opt.name().equals("TCP_KEEPINTERVAL")) {
                    tcpKeepIntervalTmp = (SocketOption<Integer>) opt;
                } else if (opt.name().equals("TCP_KEEPCOUNT")) {
                    tcpKeepCountTmp = (SocketOption<Integer>) opt;
                }
            }
        } catch (IOException e) {
            logger.warn("your jdk does not support TCP_KEEPIDLE,TCP_KEEPINTERVAL,TCP_KEEPCOUNT.");
        } finally {
            TCP_KEEPIDLE = tcpKeepIdleTmp;
            TCP_KEEPINTERVAL = tcpKeepIntervalTmp;
            TCP_KEEPCOUNT = tcpKeepCountTmp;
        }
    }

    private static void configRcvSnd(TcpSoConfig config, NetworkChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            try {
                channel.setOption(SO_RCVBUF, soRcvBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_RCVBUF");
            }
        }

        if (soSndBuf != null) {
            try {
                channel.setOption(SO_SNDBUF, soSndBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_SNDBUF");
            }
        }
    }

    /**
     * Apply TCP configuration to a listening channel.
     * @param config the TCP configuration
     * @param channel the network channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configListen(TcpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);

        channel.setOption(SO_REUSEADDR, true);
    }

    /**
     * Apply TCP configuration to a connected socket channel.
     * @param config the TCP configuration
     * @param channel the network channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configSocket(TcpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);

        if (Boolean.TRUE.equals(config.getSoKeepAlive())) {
            channel.setOption(SO_KEEPALIVE, true);
            if (config.getSoKeepIdleSec() != null && TCP_KEEPIDLE != null) {
                channel.setOption(TCP_KEEPIDLE, config.getSoKeepIdleSec());
            }
            if (config.getSoKeepIntervalSec() != null && TCP_KEEPINTERVAL != null) {
                channel.setOption(TCP_KEEPINTERVAL, config.getSoKeepIntervalSec());
            }
            if (config.getSoKeepCount() != null && TCP_KEEPCOUNT != null) {
                channel.setOption(TCP_KEEPCOUNT, config.getSoKeepCount());
            }
        }
    }
}