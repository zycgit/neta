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
package net.hasor.neta.channel.udp;
import java.io.IOException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.NetworkChannel;
import java.util.Objects;
import net.hasor.cobble.logging.Logger;

/**
 * Utility class to configure UDP socket options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class UdpSoConfigUtils {
    private static final Logger                logger       = Logger.getLogger(UdpSoConfigUtils.class);
    private static final SocketOption<Integer> SO_SNDBUF    = StandardSocketOptions.SO_SNDBUF;
    private static final SocketOption<Integer> SO_RCVBUF    = StandardSocketOptions.SO_RCVBUF;
    private static final SocketOption<Boolean> SO_REUSEADDR = StandardSocketOptions.SO_REUSEADDR;

    private static void configRcvSnd(UdpSoConfig config, NetworkChannel channel) throws IOException {
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

    public static void configListen(UdpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);
        channel.setOption(SO_REUSEADDR, true);
    }

    public static void configSocket(UdpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);
    }

    public static int getRcvPacketSize(UdpSoConfig config) {
        // rcv buffer size
        Integer rcvBufSize = config.getSoRcvBuf();
        Integer rcvPacketSize = config.getRcvPacketSize();
        if (rcvBufSize != null && rcvPacketSize != null) {
            rcvPacketSize = Math.min(rcvBufSize, rcvPacketSize);
        } else if (rcvPacketSize == null) {
            rcvPacketSize = rcvBufSize;
        }

        return Objects.requireNonNull(rcvPacketSize, "Both rcvPacketSize and rcvBufSize are missing. At least one of them must be set.");
    }

}