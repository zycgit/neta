package net.hasor.neta.channel.quic;
import java.net.SocketAddress;

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

    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
    }

    public void setRemoteAddr(SocketAddress remoteAddr) {
        this.remoteAddr = remoteAddr;
    }

    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    public void setLocalAddr(SocketAddress localAddr) {
        this.localAddr = localAddr;
    }

    public long getPeerMaxData() {
        return this.peerMaxData;
    }

    public void setPeerMaxData(long peerMaxData) {
        this.peerMaxData = peerMaxData;
    }

    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    public void setPeerMaxStreamsBidi(long peerMaxStreamsBidi) {
        this.peerMaxStreamsBidi = peerMaxStreamsBidi;
    }

    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    public void setPeerMaxStreamsUni(long peerMaxStreamsUni) {
        this.peerMaxStreamsUni = peerMaxStreamsUni;
    }

    public long getPeerStreamMaxDataBidiLocal() {
        return this.peerStreamMaxDataBidiLocal;
    }

    public void setPeerStreamMaxDataBidiLocal(long peerStreamMaxDataBidiLocal) {
        this.peerStreamMaxDataBidiLocal = peerStreamMaxDataBidiLocal;
    }

    public long getPeerStreamMaxDataBidiRemote() {
        return this.peerStreamMaxDataBidiRemote;
    }

    public void setPeerStreamMaxDataBidiRemote(long peerStreamMaxDataBidiRemote) {
        this.peerStreamMaxDataBidiRemote = peerStreamMaxDataBidiRemote;
    }

    public long getPeerStreamMaxDataUni() {
        return this.peerStreamMaxDataUni;
    }

    public void setPeerStreamMaxDataUni(long peerStreamMaxDataUni) {
        this.peerStreamMaxDataUni = peerStreamMaxDataUni;
    }

    public long getDatagramMaxDataSize() {
        return this.datagramMaxDataSize;
    }

    public void setDatagramMaxDataSize(long datagramMaxDataSize) {
        this.datagramMaxDataSize = datagramMaxDataSize;
    }
}