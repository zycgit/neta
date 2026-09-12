/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.NetworkChannel;
import java.util.Objects;
import net.hasor.cobble.logging.Logger;
/**
 * Apply settings from {@link UdpSoConfig} to {@link java.nio.channels.DatagramChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see UdpSoConfig
 */
public class UdpSoConfigUtils {
    private static final Logger                logger       = Logger.getLogger(UdpSoConfigUtils.class);
    private static final SocketOption<Integer> SO_SNDBUF    = StandardSocketOptions.SO_SNDBUF;
    private static final SocketOption<Integer> SO_RCVBUF    = StandardSocketOptions.SO_RCVBUF;
    private static final SocketOption<Boolean> SO_REUSEADDR = StandardSocketOptions.SO_REUSEADDR;

    /**
     * Apply receive/send buffer-related settings in one place.
     * @param config the UDP configuration
     * @param channel the underlying network channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
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

    /**
     * Apply UDP configuration to a listening channel.
     * @param config the UDP configuration
     * @param channel the network channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configListen(UdpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);
        channel.setOption(SO_REUSEADDR, true);
    }

    /**
     * Apply UDP configuration to a connected or ordinary socket channel.
     * @param config the UDP configuration
     * @param channel the network channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configSocket(UdpSoConfig config, NetworkChannel channel) throws IOException {
        configRcvSnd(config, channel);
    }

    /**
     * Resolve the effective receive packet size.
     * @param config the UDP configuration
     * @return the effective receive packet size
     */
    public static int getRcvPacketSize(UdpSoConfig config) {
        // Resolve the effective receive buffer size.
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
