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
package net.hasor.neta.channel.transport.sctp;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import com.sun.nio.sctp.SctpSocketOption;
import com.sun.nio.sctp.SctpStandardSocketOptions;
import com.sun.nio.sctp.SctpStandardSocketOptions.InitMaxStreams;
import net.hasor.cobble.logging.Logger;
/**
 * Helper utilities for SCTP configuration.
 * <p>Responsible for detecting the SCTP socket options supported by the current platform and for
 * applying the usable settings from {@link SctpSoConfig} to the underlying channels.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SctpSoConfig
 */
class SctpSoConfigUtils {
    private static final Logger                           logger    = Logger.getLogger(SctpSoConfigUtils.class);
    private static final SctpSocketOption<Integer>        SO_SNDBUF = SctpStandardSocketOptions.SO_SNDBUF;
    private static final SctpSocketOption<Integer>        SO_RCVBUF = SctpStandardSocketOptions.SO_RCVBUF;
    private static final SctpSocketOption<Boolean>        SCTP_DISABLE_FRAGMENTS;
    private static final SctpSocketOption<Boolean>        SCTP_EXPLICIT_COMPLETE;
    private static final SctpSocketOption<Integer>        SCTP_FRAGMENT_INTERLEAVE;
    private static final SctpSocketOption<InitMaxStreams> SCTP_INIT_MAXSTREAMS;
    private static final SctpSocketOption<Boolean>        SCTP_NODELAY;
    private static final SctpSocketOption<SocketAddress>  SCTP_PRIMARY_ADDR;
    private static final SctpSocketOption<SocketAddress>  SCTP_SET_PEER_PRIMARY_ADDR;
    private static final SctpSocketOption<Boolean>        SO_KEEPALIVE;
    private static final SctpSocketOption<Integer>        TCP_KEEPIDLE;
    private static final SctpSocketOption<Integer>        TCP_KEEPINTERVAL;
    private static final SctpSocketOption<Integer>        TCP_KEEPCOUNT;

    static {
        SctpSocketOption<Boolean> sctpDisableFragments = null;
        SctpSocketOption<Boolean> sctpExplicitComplete = null;
        SctpSocketOption<Integer> sctpFragmentInterleave = null;
        SctpSocketOption<InitMaxStreams> sctpInitMaxStreams = null;
        SctpSocketOption<Boolean> sctpNoDelay = null;
        SctpSocketOption<SocketAddress> sctpPrimaryAddr = null;
        SctpSocketOption<SocketAddress> sctpSetPeerPrimaryAddr = null;
        SctpSocketOption<Boolean> soKeepAlive = null;
        SctpSocketOption<Integer> tcpKeepIdle = null;
        SctpSocketOption<Integer> tcpKeepInterval = null;
        SctpSocketOption<Integer> tcpKeepCount = null;

        try (SctpChannel c = SctpChannel.open()) {
            Set<SctpSocketOption<?>> options = c.supportedOptions();
            for (SctpSocketOption<?> opt : options) {
                if (opt.name().equals("SCTP_DISABLE_FRAGMENTS")) {
                    sctpDisableFragments = (SctpSocketOption<Boolean>) opt;
                } else if (opt.name().equals("SCTP_EXPLICIT_COMPLETE")) {
                    sctpExplicitComplete = (SctpSocketOption<Boolean>) opt;
                } else if (opt.name().equals("SCTP_FRAGMENT_INTERLEAVE")) {
                    sctpFragmentInterleave = (SctpSocketOption<Integer>) opt;
                } else if (opt.name().equals("SCTP_INIT_MAXSTREAMS")) {
                    sctpInitMaxStreams = (SctpSocketOption<InitMaxStreams>) opt;
                } else if (opt.name().equals("SCTP_NODELAY")) {
                    sctpNoDelay = (SctpSocketOption<Boolean>) opt;
                } else if (opt.name().equals("SCTP_PRIMARY_ADDR")) {
                    sctpPrimaryAddr = (SctpSocketOption<SocketAddress>) opt;
                } else if (opt.name().equals("SCTP_SET_PEER_PRIMARY_ADDR")) {
                    sctpSetPeerPrimaryAddr = (SctpSocketOption<SocketAddress>) opt;
                } else if (opt.name().equals("SO_KEEPALIVE")) {
                    soKeepAlive = (SctpSocketOption<Boolean>) opt;
                } else if (opt.name().equals("TCP_KEEPIDLE")) {
                    tcpKeepIdle = (SctpSocketOption<Integer>) opt;
                } else if (opt.name().equals("TCP_KEEPINTERVAL")) {
                    tcpKeepInterval = (SctpSocketOption<Integer>) opt;
                } else if (opt.name().equals("TCP_KEEPCOUNT")) {
                    tcpKeepCount = (SctpSocketOption<Integer>) opt;
                }
            }
        } catch (Throwable e) {
            logger.warn("your jdk does not support SCTP options, " + e.getMessage());
        } finally {
            SCTP_DISABLE_FRAGMENTS = sctpDisableFragments;
            SCTP_EXPLICIT_COMPLETE = sctpExplicitComplete;
            SCTP_FRAGMENT_INTERLEAVE = sctpFragmentInterleave;
            SCTP_INIT_MAXSTREAMS = sctpInitMaxStreams;
            SCTP_NODELAY = sctpNoDelay;
            SCTP_PRIMARY_ADDR = sctpPrimaryAddr;
            SCTP_SET_PEER_PRIMARY_ADDR = sctpSetPeerPrimaryAddr;
            SO_KEEPALIVE = soKeepAlive;
            TCP_KEEPIDLE = tcpKeepIdle;
            TCP_KEEPINTERVAL = tcpKeepInterval;
            TCP_KEEPCOUNT = tcpKeepCount;
        }
    }

    private static <T> void setOption(SctpServerChannel channel, SctpSocketOption<T> option, T value, String optionName) throws IOException {
        if (option == null || value == null) {
            return;
        }
        try {
            channel.setOption(option, value);
        } catch (UnsupportedOperationException e) {
            logger.warn("the platform does not support " + optionName);
        }
    }

    private static <T> void setOption(SctpChannel channel, SctpSocketOption<T> option, T value, String optionName) throws IOException {
        if (option == null || value == null) {
            return;
        }
        try {
            channel.setOption(option, value);
        } catch (UnsupportedOperationException e) {
            logger.warn("the platform does not support " + optionName);
        }
    }

    private static void configRcvSnd(SctpSoConfig config, SctpServerChannel channel) throws IOException {
        setOption(channel, SO_RCVBUF, config.getSoRcvBuf(), "SO_RCVBUF");
        setOption(channel, SO_SNDBUF, config.getSoSndBuf(), "SO_SNDBUF");
    }

    private static void configRcvSnd(SctpSoConfig config, SctpChannel channel) throws IOException {
        setOption(channel, SO_RCVBUF, config.getSoRcvBuf(), "SO_RCVBUF");
        setOption(channel, SO_SNDBUF, config.getSoSndBuf(), "SO_SNDBUF");
    }

    /**
     * Apply SCTP-related configuration to a listening channel.
     * <p>The listen phase applies the common send and receive buffer settings.
     * @param config the SCTP configuration
     * @param channel the SCTP server channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configListen(SctpSoConfig config, SctpServerChannel channel) throws IOException {
        configRcvSnd(config, channel);
    }

    /**
     * Apply configuration to a connected or accepted SCTP socket.
     * @param config the SCTP configuration
     * @param channel the SCTP channel
     * @throws IOException if an I/O error occurs while applying the configuration
     */
    public static void configSocket(SctpSoConfig config, SctpChannel channel) throws IOException {
        configRcvSnd(config, channel);

        if (config.getSoKeepAlive() != null) {
            setOption(channel, SO_KEEPALIVE, config.getSoKeepAlive(), "SO_KEEPALIVE");
        }
        if (Boolean.TRUE.equals(config.getSoKeepAlive())) {
            setOption(channel, TCP_KEEPIDLE, config.getSoKeepIdleSec(), "TCP_KEEPIDLE");
            setOption(channel, TCP_KEEPINTERVAL, config.getSoKeepIntervalSec(), "TCP_KEEPINTERVAL");
            setOption(channel, TCP_KEEPCOUNT, config.getSoKeepCount(), "TCP_KEEPCOUNT");
        }
    }

    /**
     * Calculate the buffer size used for receiving a single packet.
     * <p>When both the underlying receive buffer and the swap buffer are configured, the smaller
     * value is used. When only one of them is configured, that value is used directly.
     * @param config the SCTP configuration
     * @return the receive buffer size for a single packet
     */
    public static int getRcvPacketSize(SctpSoConfig config) {
        // rcv buffer size
        Integer rcvBufSize = config.getSoRcvBuf();
        Integer rcvPacketSize = config.getSwapRcvBuf();
        if (rcvBufSize != null && rcvPacketSize != null) {
            rcvPacketSize = Math.min(rcvBufSize, rcvPacketSize);
        } else if (rcvPacketSize == null) {
            rcvPacketSize = rcvBufSize;
        }

        return Objects.requireNonNull(rcvPacketSize, "Both rcvPacketSize and rcvBufSize are missing. At least one of them must be set.");
    }

    /**
     * Return the initial size of the send swap buffer.
     * @param config the SCTP configuration
     * @return the send swap buffer size
     */
    public static int getSndPacketSize(SctpSoConfig config) {
        return Math.max(1, config.getSwapSndBuf());
    }
}