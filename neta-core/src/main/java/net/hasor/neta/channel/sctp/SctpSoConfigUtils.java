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
package net.hasor.neta.channel.sctp;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import com.sun.nio.sctp.SctpSocketOption;
import com.sun.nio.sctp.SctpStandardSocketOptions.InitMaxStreams;
import net.hasor.cobble.logging.Logger;

/**
 * Utility class for configuring SCTP Socket options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SctpSoConfigUtils {
    private static final Logger logger = Logger.getLogger(SctpSoConfigUtils.class);

    //SCTP_DISABLE_FRAGMENTS        Enables or disables message fragmentation
    //SCTP_EXPLICIT_COMPLETE        Enables or disables explicit message completion
    //SCTP_FRAGMENT_INTERLEAVE      Controls how the presentation of messages occur for the message receiver
    //SCTP_INIT_MAXSTREAMS          The maximum number of streams requested by the local endpoint during association initialization
    //SCTP_NODELAY                  Enables or disable a Nagle-like algorithm
    //SCTP_PRIMARY_ADDR             Requests that the local SCTP stack use the given peer address as the association primary
    //SCTP_SET_PEER_PRIMARY_ADDR    Requests that the peer mark the enclosed address as the association primary
    //SO_SNDBUF                     The size of the socket send buffer
    //SO_RCVBUF                     The size of the socket receive buffer
    //SO_LINGER                     Linger on close if data is present (when configured in blocking mode only)

    private static final SctpSocketOption<Boolean>        SCTP_DISABLE_FRAGMENTS;
    private static final SctpSocketOption<Boolean>        SCTP_EXPLICIT_COMPLETE;
    private static final SctpSocketOption<Integer>        SCTP_FRAGMENT_INTERLEAVE;
    private static final SctpSocketOption<InitMaxStreams> SCTP_INIT_MAXSTREAMS;
    private static final SctpSocketOption<Boolean>        SCTP_NODELAY;
    private static final SctpSocketOption<SocketAddress>  SCTP_PRIMARY_ADDR;
    private static final SctpSocketOption<SocketAddress>  SCTP_SET_PEER_PRIMARY_ADDR;

    static {
        SctpSocketOption<Boolean> sctpDisableFragments = null;
        SctpSocketOption<Boolean> sctpExplicitComplete = null;
        SctpSocketOption<Integer> sctpFragmentInterleave = null;
        SctpSocketOption<InitMaxStreams> sctpInitMaxStreams = null;
        SctpSocketOption<Boolean> sctpNoDelay = null;
        SctpSocketOption<SocketAddress> sctpPrimaryAddr = null;
        SctpSocketOption<SocketAddress> sctpSetPeerPrimaryAddr = null;

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
                }
            }
        } catch (IOException e) {
            logger.warn("your jdk does not support SCTP options, " + e.getMessage());
        } finally {
            SCTP_DISABLE_FRAGMENTS = sctpDisableFragments;
            SCTP_EXPLICIT_COMPLETE = sctpExplicitComplete;
            SCTP_FRAGMENT_INTERLEAVE = sctpFragmentInterleave;
            SCTP_INIT_MAXSTREAMS = sctpInitMaxStreams;
            SCTP_NODELAY = sctpNoDelay;
            SCTP_PRIMARY_ADDR = sctpPrimaryAddr;
            SCTP_SET_PEER_PRIMARY_ADDR = sctpSetPeerPrimaryAddr;
        }
    }

    public static void configListen(SctpSoConfig config, SctpServerChannel channel) throws IOException {
    }

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
}