package net.hasor.cobble.net.ssl;
import net.hasor.cobble.net.SoResManager;

import java.nio.ByteBuffer;

class SslBuffers {
    public final SoResManager rm;
    public       ByteBuffer   inNetData;
    public       ByteBuffer   inAppData;
    public       ByteBuffer   outNetData;
    public       ByteBuffer   outAppData;

    public SslBuffers(SoResManager rm) {
        this.rm = rm;
    }

    /** 初始化 buffers */
    public void initBuffers(int appBufSize, int packetBufSize) {
        this.inAppData = this.rm.newByteBuffer(appBufSize);
        this.inNetData = this.rm.newByteBuffer(packetBufSize);
        this.outAppData = this.rm.newByteBuffer(appBufSize);
        this.outNetData = this.rm.newByteBuffer(packetBufSize);
    }

    /** 释放 buffers */
    public void freeBuffers() {
        this.rm.freeObject(this.inAppData);
        this.rm.freeObject(this.inNetData);
        this.rm.freeObject(this.outAppData);
        this.rm.freeObject(this.outNetData);
        this.inAppData = null;
        this.inNetData = null;
        this.outAppData = null;
        this.outNetData = null;
    }

}
