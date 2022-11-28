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
package net.hasor.cobble.bytebuf;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static net.hasor.cobble.bytebuf.Bits.*;

/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractByteBuf implements ByteBuf {
    protected       int           markedReaderIndex;
    protected       int           markedWriterIndex;
    protected       int           readerIndex;
    protected       int           writerIndex;
    private final   int           maxCapacity;
    private         boolean       isFree;
    protected final ReadWriteLock lock;

    protected AbstractByteBuf(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.lock = new ReentrantReadWriteLock();
        this.isFree = false;
    }

    public int getMaxCapacity() {
        return this.maxCapacity;
    }

    protected void checkFree() {
        if (this.isFree) {
            throw new IllegalStateException("has been released.");
        }
    }

    @Override
    public boolean isFree() {
        return this.isFree;
    }

    protected abstract void _putByte(int offset, byte b);

    protected abstract void _putBytes(int offset, byte[] b, int off, int len);

    protected abstract byte _getByte(int offset);

    protected abstract int _getBytes(int offset, byte[] b, int off, int len);

    /** 需要扩充到的目标容量 */
    protected abstract void extendByteBuf(int targetCapacity);

    /** 回收 markedReaderIndex 之前的内存区块 */
    protected void recycleByteBuf() throws IOException {
    }

    /**  markedWriterIndex 向前推进，有更多的数据可读 */
    protected void receivedBytes(int lastMarkedWriter, int currentMarkedWriter) throws IOException {
    }

    @Override
    public int writableBytes() {
        return this.capacity() - (this.writerIndex() - this.markedReaderIndex);
    }

    // 先扩容在移指针
    // 1. 检查或扩充 Buffer 确保可以写入 writableBytes 所需要的字节数
    // 2. 移动 writerIndex 指针到 writableBytes 字节数之后
    // 3. 返回 writerIndex 变化前的值
    protected synchronized int nextWritable(int writableBytes) {
        if (writableBytes > writableBytes()) {
            int targetCapacity = (this.writerIndex + writableBytes) - this.markedReaderIndex;
            this.extendByteBuf(targetCapacity);
        }

        int oriWriterIndex = this.writerIndex;
        this.writerIndex += writableBytes;
        return oriWriterIndex - this.markedReaderIndex;
    }

    // 先检查在移指针
    // 1. 检查 Buffer 确保从 readIndex 位置开始可以读取 readableBytes 的字节数。
    // 2. 移动 readIndex 指针到 readableBytes 字节数之后
    // 3. 返回 readIndex 变化前的值
    protected synchronized int nextReadable(int readableBytes) {
        int oriReadIndex = this.readerIndex;
        if ((oriReadIndex + readableBytes) <= this.markedWriterIndex) {
            this.readerIndex += readableBytes;
            return oriReadIndex - this.markedReaderIndex;
        }

        throw new IndexOutOfBoundsException(String.format("read out of range. (readerIndex(%d) + readableBytes(%d)) <= writerMark(%d)", this.readerIndex, readableBytes, this.markedWriterIndex));
    }

    // readMark <= readIndex <= writerMark           <= writerIndex <= capacity
    //                          writerMark <= offset <= writerIndex
    protected int offsetWritable(int offset, int len) {
        if (this.markedWriterIndex <= offset && offset <= this.writerIndex) {
            int targetCapacity = offset + len - this.markedReaderIndex;
            if (targetCapacity > this.capacity()) {
                throw new BufferOverflowException();
            }
            return offset;
        }
        throw new IndexOutOfBoundsException(String.format("write data(%d) out of range. writerMark(%d) <= offset(%d) <= writerIndex(%d)", len, this.markedWriterIndex, offset, this.writerIndex));
    }

    // readMark <= readIndex <= writerMark <= writerIndex <= capacity
    // readMark <= offset    <= writerMark
    protected int checkReadable(int offset, int len) {
        int oriReadIndex = this.markedReaderIndex;
        if ((oriReadIndex + offset + len) <= this.markedWriterIndex) {
            return offset;
        }
        throw new IndexOutOfBoundsException(String.format("read data(%d) out of range. readMark(%d) <= offset(%d) <= writerMark(%d)", len, this.markedReaderIndex, offset, this.markedWriterIndex));
    }

    @Override
    public ByteBuf skipReadableBytes(int length) {
        nextReadable(length);
        return this;
    }

    @Override
    public ByteBuf skipWritableBytes(int length) {
        nextWritable(length);
        return this;
    }

    @Override
    public synchronized ByteBuf markReader() throws IOException {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.recycleByteBuf();
        }
        return this;
    }

    @Override
    public ByteBuf resetReader() {
        this.readerIndex = this.markedReaderIndex;
        return this;
    }

    @Override
    public int readerIndex() {
        return this.readerIndex;
    }

    protected int getMarkedReaderIndex() {
        return this.markedReaderIndex;
    }

    @Override
    public ByteBuf markWriter() throws IOException {
        if (this.markedWriterIndex != this.writerIndex) {
            int lastMarkedWriter = this.markedWriterIndex;
            this.markedWriterIndex = this.writerIndex;
            this.receivedBytes(lastMarkedWriter, this.markedWriterIndex);
        }
        return this;
    }

    @Override
    public ByteBuf resetWriter() {
        this.writerIndex = this.markedWriterIndex;
        return this;
    }

    @Override
    public int writerIndex() {
        return this.writerIndex;
    }

    //    @Override
    //    public ByteBuf asReadOnly() {
    //        return new ReadOnlyByteBuf(this);
    //    }

    protected int getMarkedWriterIndex() {
        return this.markedWriterIndex;
    }

    @Override
    public void writeByte(byte n) {
        this._putByte(nextWritable(1), n);
    }

    @Override
    public void writeBytes(byte[] b, int off, int len) {
        this._putBytes(nextWritable(len), b, off, len);
    }

    @Override
    public void writeInt16(short n) {
        encodeInt16(this, nextWritable(2), n, true);
    }

    @Override
    public void writeInt24(int n) {
        encodeInt24(this, nextWritable(3), n, true);
    }

    @Override
    public void writeInt32(int n) {
        encodeInt32(this, nextWritable(4), n, true);
    }

    @Override
    public void writeInt64(long n) {
        encodeInt64(this, nextWritable(8), n, true);
    }

    @Override
    public void writeInt16LE(short n) {
        encodeInt16(this, nextWritable(2), n, false);
    }

    @Override
    public void writeInt24LE(int n) {
        encodeInt24(this, nextWritable(3), n, false);
    }

    @Override
    public void writeInt32LE(int n) {
        encodeInt32(this, nextWritable(4), n, false);
    }

    @Override
    public void writeInt64LE(long n) {
        encodeInt64(this, nextWritable(8), n, false);
    }

    @Override
    public void setByte(int offset, byte n) {
        this._putByte(offsetWritable(offset, 1), n);
    }

    @Override
    public void setBytes(int offset, byte[] b) {
        this._putBytes(offsetWritable(offset, b.length), b, 0, b.length);
    }

    @Override
    public void setBytes(int offset, byte[] b, int off, int len) {
        this._putBytes(offsetWritable(offset, len), b, off, len);
    }

    @Override
    public void setInt16(int offset, short n) {
        encodeInt16(this, offsetWritable(offset, 2), n, true);
    }

    @Override
    public void setInt24(int offset, int n) {
        encodeInt24(this, offsetWritable(offset, 3), n, true);
    }

    @Override
    public void setInt32(int offset, int n) {
        encodeInt32(this, offsetWritable(offset, 4), n, true);
    }

    @Override
    public void setInt64(int offset, long n) {
        encodeInt64(this, offsetWritable(offset, 8), n, true);
    }

    @Override
    public void setInt16LE(int offset, short n) {
        encodeInt16(this, offsetWritable(offset, 2), n, false);
    }

    @Override
    public void setInt24LE(int offset, int n) {
        encodeInt24(this, offsetWritable(offset, 3), n, false);
    }

    @Override
    public void setInt32LE(int offset, int n) {
        encodeInt32(this, offsetWritable(offset, 4), n, false);
    }

    @Override
    public void setInt64LE(int offset, long n) {
        encodeInt64(this, offsetWritable(offset, 8), n, false);
    }

    @Override
    public byte readByte() {
        return this._getByte(nextReadable(1));
    }

    @Override
    public int readBytes(byte[] b, int off, int len) {
        int minLen = Math.min(len, this.readableBytes());
        return this._getBytes(nextReadable(minLen), b, off, minLen);
    }

    @Override
    public short readInt16() {
        return dencodeInt16(this, nextReadable(2), true);
    }

    @Override
    public int readInt24() {
        return dencodeInt24(this, nextReadable(3), true);
    }

    @Override
    public int readInt32() {
        return dencodeInt32(this, nextReadable(4), true);
    }

    @Override
    public long readInt64() {
        return dencodeInt64(this, nextReadable(8), true);
    }

    @Override
    public short readInt16LE() {
        return dencodeInt16(this, nextReadable(2), false);
    }

    @Override
    public int readInt24LE() {
        return dencodeInt24(this, nextReadable(3), false);
    }

    @Override
    public int readInt32LE() {
        return dencodeInt32(this, nextReadable(4), false);
    }

    @Override
    public long readInt64LE() {
        return dencodeInt64(this, nextReadable(8), false);
    }

    @Override
    public byte getByte(int offset) {
        return this._getByte(checkReadable(offset, 1));
    }

    @Override
    public int getBytes(int offset, byte[] b, int off, int len) {
        if ((offset + len) <= this.writerIndex()) {
            return this._getBytes(offset, b, off, len);
        } else {
            int readableBytes = this.writerIndex() - (offset + len);
            if (readableBytes < 0) {
                return -1;
            } else {
                return this._getBytes(offset, b, off, readableBytes);
            }
        }
    }

    @Override
    public short getInt16(int offset) {
        return dencodeInt16(this, checkReadable(offset, 2), true);
    }

    @Override
    public int getInt24(int offset) {
        return dencodeInt24(this, checkReadable(offset, 3), true);
    }

    @Override
    public int getInt32(int offset) {
        return dencodeInt32(this, checkReadable(offset, 4), true);
    }

    @Override
    public long getInt64(int offset) {
        return dencodeInt64(this, checkReadable(offset, 8), true);
    }

    @Override
    public short getInt16LE(int offset) {
        return dencodeInt16(this, checkReadable(offset, 2), false);
    }

    @Override
    public int getInt24LE(int offset) {
        return dencodeInt24(this, checkReadable(offset, 3), false);
    }

    @Override
    public int getInt32LE(int offset) {
        return dencodeInt32(this, checkReadable(offset, 4), false);
    }

    @Override
    public long getInt64LE(int offset) {
        return dencodeInt64(this, checkReadable(offset, 8), false);
    }

    @Override
    public short readUInt8() {
        return dencodeUInt8(this, nextReadable(1));
    }

    @Override
    public int readUInt16() {
        return dencodeUInt16(this, nextReadable(2), true);
    }

    @Override
    public int readUInt24() {
        return dencodeUInt24(this, nextReadable(3), true);
    }

    @Override
    public long readUInt32() {
        return dencodeUInt32(this, nextReadable(4), true);
    }

    @Override
    public int readUInt16LE() {
        return dencodeUInt16(this, nextReadable(2), false);
    }

    @Override
    public int readUInt24LE() {
        return dencodeUInt24(this, nextReadable(3), false);
    }

    @Override
    public long readUInt32LE() {
        return dencodeUInt32(this, nextReadable(4), false);
    }

    @Override
    public short getUInt8(int offset) {
        return dencodeUInt8(this, checkReadable(offset, 1));
    }

    @Override
    public int getUInt16(int offset) {
        return dencodeUInt16(this, checkReadable(offset, 2), true);
    }

    @Override
    public int getUInt24(int offset) {
        return dencodeUInt24(this, checkReadable(offset, 3), true);
    }

    @Override
    public long getUInt32(int offset) {
        return dencodeUInt32(this, checkReadable(offset, 4), true);
    }

    @Override
    public int getUInt16LE(int offset) {
        return dencodeUInt16(this, checkReadable(offset, 2), false);
    }

    @Override
    public int getUInt24LE(int offset) {
        return dencodeUInt24(this, checkReadable(offset, 3), false);
    }

    @Override
    public long getUInt32LE(int offset) {
        return dencodeUInt32(this, checkReadable(offset, 4), false);
    }

    @Override
    public int read(ByteBuffer dst) {
        int copied = 0;
        int srcReadableBytes;
        byte[] buf = new byte[4096];

        while (true) {
            if ((srcReadableBytes = this.readableBytes()) == 0 || dst.remaining() == 0) {
                break;
            }

            int len = Math.min(dst.remaining(), Math.min(buf.length, srcReadableBytes));
            int readBytes = this.readBytes(buf, 0, len);
            if (readBytes <= 0) {
                break;
            }
            dst.put(buf, copied, readBytes);
            copied += readBytes;
        }

        return copied;
    }

    @Override
    public int write(ByteBuffer src) {
        int copied = 0;
        byte[] buf = new byte[4096];

        while (true) {
            if (src.remaining() == 0 || this.writableBytes() == 0) {
                break;
            }

            int len = Math.min(this.writableBytes(), Math.min(buf.length, src.remaining()));
            if (len <= 0) {
                break;
            }

            src.get(buf, 0, len);
            this.writeBytes(buf, 0, len);
            copied += len;
        }

        return copied;
    }

    @Override
    public boolean isOpen() {
        return this.isFree();
    }

    @Override
    public void close() {
        this.free();
    }

    @Override
    public void free() {
        this.isFree = true;
    }
}
