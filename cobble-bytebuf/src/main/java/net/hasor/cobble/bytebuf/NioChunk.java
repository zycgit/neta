package net.hasor.cobble.bytebuf;
import java.nio.ByteBuffer;

class NioChunk {
    private final ByteBuffer byteBuffer;

    NioChunk(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
    }

    byte get(int index) {
        return this.byteBuffer.get(index);
    }

    void put(int index, byte b) {
        this.byteBuffer.put(index, b);
    }

    void get(byte[] dst, int offset, int length) {
        this.byteBuffer.get(dst, offset, length);
    }

    void put(byte[] src, int offset, int length) {
        this.byteBuffer.put(src, offset, length);
    }

    void clearPosition(int position) {
        this.byteBuffer.clear().position(position);
    }

    void clearLimit(int limit) {
        this.byteBuffer.clear().limit(limit);
    }

    void position(int position) {
        this.byteBuffer.position(position);
    }

    int capacity() {
        return this.byteBuffer.capacity();
    }

    void deepCopy(NioChunk dst) {
        //        dst.clearLimit();
    }

    void freeBuffer() {
        ByteBufUtil.CLEANER.freeDirectBuffer(this.byteBuffer);
    }
}
