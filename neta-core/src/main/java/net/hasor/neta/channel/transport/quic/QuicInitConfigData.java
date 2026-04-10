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
package net.hasor.neta.channel.transport.quic;
import java.net.SocketAddress;

/**
 * Stores the QUIC transport parameters negotiated during the handshake phase.
 * <p>Corresponds to RFC 9000 Section 18 and is used internally to pass peer configuration from the handshake phase to the post-handshake connection channel.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicInitConfigData {
    private SocketAddress remoteAddr;
    private SocketAddress localAddr;
    private long          peerMaxData;
    private long          peerMaxStreamsBidi;
    private long          peerMaxStreamsUni;
    private long          peerStreamMaxDataBidiLocal;
    private long          peerStreamMaxDataBidiRemote;
    private long          peerStreamMaxDataUni;
    private long          datagramMaxDataSize;

    /**
     * Returns the peer remote address.
     */
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
    }

    /**
     * Sets the peer remote address.
     */
    public void setRemoteAddr(SocketAddress remoteAddr) {
        this.remoteAddr = remoteAddr;
    }

    /**
     * Returns the local address.
     */
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    /**
     * Sets the local address.
     */
    public void setLocalAddr(SocketAddress localAddr) {
        this.localAddr = localAddr;
    }

    /**
     * Returns the initial_max_data parameter advertised by the peer.
     */
    public long getPeerMaxData() {
        return this.peerMaxData;
    }

    /**
     * Sets the initial_max_data parameter advertised by the peer.
     */
    public void setPeerMaxData(long peerMaxData) {
        this.peerMaxData = peerMaxData;
    }

    /**
     * Returns the initial_max_streams_bidi parameter advertised by the peer.
     */
    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    /**
     * Sets the initial_max_streams_bidi parameter advertised by the peer.
     */
    public void setPeerMaxStreamsBidi(long peerMaxStreamsBidi) {
        this.peerMaxStreamsBidi = peerMaxStreamsBidi;
    }

    /**
     * Returns the initial_max_streams_uni parameter advertised by the peer.
     */
    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    /**
     * Sets the initial_max_streams_uni parameter advertised by the peer.
     */
    public void setPeerMaxStreamsUni(long peerMaxStreamsUni) {
        this.peerMaxStreamsUni = peerMaxStreamsUni;
    }

    /**
     * Returns the initial_max_stream_data_bidi_local parameter advertised by the peer.
     */
    public long getPeerStreamMaxDataBidiLocal() {
        return this.peerStreamMaxDataBidiLocal;
    }

    /**
     * Sets the initial_max_stream_data_bidi_local parameter advertised by the peer.
     */
    public void setPeerStreamMaxDataBidiLocal(long peerStreamMaxDataBidiLocal) {
        this.peerStreamMaxDataBidiLocal = peerStreamMaxDataBidiLocal;
    }

    /**
     * Returns the initial_max_stream_data_bidi_remote parameter advertised by the peer.
     */
    public long getPeerStreamMaxDataBidiRemote() {
        return this.peerStreamMaxDataBidiRemote;
    }

    /**
     * Sets the initial_max_stream_data_bidi_remote parameter advertised by the peer.
     */
    public void setPeerStreamMaxDataBidiRemote(long peerStreamMaxDataBidiRemote) {
        this.peerStreamMaxDataBidiRemote = peerStreamMaxDataBidiRemote;
    }

    /**
     * Returns the initial_max_stream_data_uni parameter advertised by the peer.
     */
    public long getPeerStreamMaxDataUni() {
        return this.peerStreamMaxDataUni;
    }

    /**
     * Sets the initial_max_stream_data_uni parameter advertised by the peer.
     */
    public void setPeerStreamMaxDataUni(long peerStreamMaxDataUni) {
        this.peerStreamMaxDataUni = peerStreamMaxDataUni;
    }

    /**
     * Returns the max_datagram_frame_size parameter advertised by the peer.
     */
    public long getDatagramMaxDataSize() {
        return this.datagramMaxDataSize;
    }

    /**
     * Sets the max_datagram_frame_size parameter advertised by the peer.
     */
    public void setDatagramMaxDataSize(long datagramMaxDataSize) {
        this.datagramMaxDataSize = datagramMaxDataSize;
    }
}