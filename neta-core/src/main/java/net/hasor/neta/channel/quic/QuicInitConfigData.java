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
package net.hasor.neta.channel.quic;
import java.net.SocketAddress;

/**
 * Holds QUIC transport parameters negotiated during the handshake (RFC 9000 §18).
 * Used internally to pass peer configuration from handshake to the post-handshake channel.
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

    /** Returns the peer's remote address. */
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
    }

    /** Sets the peer's remote address. */
    public void setRemoteAddr(SocketAddress remoteAddr) {
        this.remoteAddr = remoteAddr;
    }

    /** Returns the peer's local address. */
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    /** Sets the peer's local address. */
    public void setLocalAddr(SocketAddress localAddr) {
        this.localAddr = localAddr;
    }

    /** Returns the peer's initial_max_data transport parameter. */
    public long getPeerMaxData() {
        return this.peerMaxData;
    }

    /** Sets the peer's initial_max_data transport parameter. */
    public void setPeerMaxData(long peerMaxData) {
        this.peerMaxData = peerMaxData;
    }

    /** Returns the peer's initial_max_streams_bidi transport parameter. */
    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    /** Sets the peer's initial_max_streams_bidi transport parameter. */
    public void setPeerMaxStreamsBidi(long peerMaxStreamsBidi) {
        this.peerMaxStreamsBidi = peerMaxStreamsBidi;
    }

    /** Returns the peer's initial_max_streams_uni transport parameter. */
    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    /** Sets the peer's initial_max_streams_uni transport parameter. */
    public void setPeerMaxStreamsUni(long peerMaxStreamsUni) {
        this.peerMaxStreamsUni = peerMaxStreamsUni;
    }

    /** Returns the peer's initial_max_stream_data_bidi_local transport parameter. */
    public long getPeerStreamMaxDataBidiLocal() {
        return this.peerStreamMaxDataBidiLocal;
    }

    /** Sets the peer's initial_max_stream_data_bidi_local transport parameter. */
    public void setPeerStreamMaxDataBidiLocal(long peerStreamMaxDataBidiLocal) {
        this.peerStreamMaxDataBidiLocal = peerStreamMaxDataBidiLocal;
    }

    /** Returns the peer's initial_max_stream_data_bidi_remote transport parameter. */
    public long getPeerStreamMaxDataBidiRemote() {
        return this.peerStreamMaxDataBidiRemote;
    }

    /** Sets the peer's initial_max_stream_data_bidi_remote transport parameter. */
    public void setPeerStreamMaxDataBidiRemote(long peerStreamMaxDataBidiRemote) {
        this.peerStreamMaxDataBidiRemote = peerStreamMaxDataBidiRemote;
    }

    /** Returns the peer's initial_max_stream_data_uni transport parameter. */
    public long getPeerStreamMaxDataUni() {
        return this.peerStreamMaxDataUni;
    }

    /** Sets the peer's initial_max_stream_data_uni transport parameter. */
    public void setPeerStreamMaxDataUni(long peerStreamMaxDataUni) {
        this.peerStreamMaxDataUni = peerStreamMaxDataUni;
    }

    /** Returns the peer's max_datagram_frame_size transport parameter (RFC 9221). */
    public long getDatagramMaxDataSize() {
        return this.datagramMaxDataSize;
    }

    /** Sets the peer's max_datagram_frame_size transport parameter. */
    public void setDatagramMaxDataSize(long datagramMaxDataSize) {
        this.datagramMaxDataSize = datagramMaxDataSize;
    }
}