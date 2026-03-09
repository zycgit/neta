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
package net.hasor.neta.channel.tcp;
import java.io.IOException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.NetworkChannel;
import java.util.Set;
import net.hasor.cobble.logging.Logger;

/**
 * Applies {@link TcpSoConfig} options to {@link java.nio.channels.AsynchronousSocketChannel}
 * and {@link java.nio.channels.AsynchronousServerSocketChannel} instances.
 * <p>The class is package-private because callers always go through the channel factory
 * ({@link TcpProvider}), never binding to this utility directly.
 * <p><b>Socket options configured:</b>
 * <ul>
 *   <li>{@code SO_RCVBUF} / {@code SO_SNDBUF}: applied via {@link TcpSoConfig#getSoRcvBuf()}
 *       and {@link TcpSoConfig#getSoSndBuf()} when non-null.  The operation is wrapped in
 *       {@code UnsupportedOperationException} catch to survive platforms that ignore these
 *       options (e.g. some embedded JVMs).</li>
 *   <li>{@code SO_REUSEADDR}: always set to {@code true} on the server listen socket to
 *       allow rapid port reuse after restart.</li>
 *   <li>{@code SO_KEEPALIVE}, {@code TCP_KEEPIDLE}, {@code TCP_KEEPINTERVAL},
 *       {@code TCP_KEEPCOUNT}: applied on client/connected sockets when
 *       {@link TcpSoConfig#getSoKeepAlive()} is {@code true}.  The three KEEPIDLE /
 *       KEEPINTERVAL / KEEPCOUNT options are discovered at class-load time via
 *       a probe {@link java.nio.channels.AsynchronousSocketChannel}; if the JDK or OS does
 *       not support them the corresponding static fields remain {@code null} and the
 *       option is silently skipped.</li>
 * </ul>
 * <p><b>Platform notes:</b>
 * <ul>
 *   <li>On Windows, {@code TCP_KEEPIDLE/KEEPINTERVAL/KEEPCOUNT} require JDK 11+ and
 *       Windows 10 / Server 2019 or later.</li>
 *   <li>On macOS, all three are available on JDK 11+ with macOS 10.15+.</li>
 *   <li>On older JDK/kernel combinations the static fields will be {@code null} and
 *       a WARN is logged at class-load time.</li>
 * </ul>
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

    public static void configListen(TcpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);

        channel.setOption(SO_REUSEADDR, true);
    }

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