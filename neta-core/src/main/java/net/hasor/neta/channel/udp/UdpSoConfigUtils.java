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
 * Applies {@link UdpSoConfig} options to {@link java.nio.channels.DatagramChannel}
 * instances and resolves the effective receive packet size.
 * <p><b>Socket options configured:</b>
 * <ul>
 *   <li>{@code SO_RCVBUF} / {@code SO_SNDBUF}: set when the corresponding field in
 *       {@link UdpSoConfig} is non-null.  Silently skipped on platforms that reject
 *       the option ({@code UnsupportedOperationException}).</li>
 *   <li>{@code SO_REUSEADDR}: always set to {@code true} on the server socket to
 *       allow the same port to be reused after restart or unclean shutdown.</li>
 * </ul>
 * <p><b>Receive packet size resolution ({@link #getRcvPacketSize}):</b>
 * The method returns the effective maximum datagram size as:
 * <ol>
 *   <li>If both {@code soRcvBuf} and {@code rcvPacketSize} are set: {@code min} of both.</li>
 *   <li>If only {@code rcvPacketSize} is set: use it directly.</li>
 *   <li>If only {@code soRcvBuf} is set: use it as a fallback.</li>
 *   <li>If neither is set: throws {@link NullPointerException} to force the
 *       caller to provide at least one of them.</li>
 * </ol>
 * This value is used as the capacity of the per-channel receive {@link java.nio.ByteBuffer}.
 * Any incoming datagram larger than this size will be silently truncated by the OS.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see UdpSoConfig
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