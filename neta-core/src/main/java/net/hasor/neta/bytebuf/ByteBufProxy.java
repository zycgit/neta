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
package net.hasor.neta.bytebuf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Transparent {@link ByteBuf} proxy that forwards every operation to a target
 * buffer.
 * <p>
 * This class is the simplest delegation layer in the bytebuf package: it does
 * not introduce new storage, new indexes, or new lifecycle rules. Subclasses
 * can override only the operations they want to intercept while leaving the
 * rest of the {@link ByteBuf} contract unchanged.
 * <p>
 * Typical uses include:
 * <ul>
 *   <li>adding read-only behavior;</li>
 *   <li>exposing a restricted view;</li>
 *   <li>injecting metrics, tracing, or guard checks around selected methods.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version 2022-11-01
 */
public class ByteBufProxy implements ByteBuf {
    private final ByteBuf target;

    public ByteBufProxy(ByteBuf target) {
        this.target = Objects.requireNonNull(target);
    }

    @Override
    public ByteBufAllocator alloc() {
        return this.target.alloc();
    }

    @Override
    public int readerIndex() {
        return this.target.readerIndex();
    }

    @Override
    public int writerIndex() {
        return this.target.writerIndex();
    }

    @Override
    public int capacity() {
        return this.target.capacity();
    }

    @Override
    public byte[] asByteArray() {
        return this.target.asByteArray();
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public ByteBuf copy() {
        return this.target.copy();
    }

    @Override
    public ByteBuf asReadOnly() {
        return new ReadOnlyByteBuf(this);
    }

    @Override
    public ByteOrder order() {
        return this.target.order();
    }

    @Override
    public ByteBuf order(ByteOrder newOrder) {
        return this.target.order(newOrder);
    }

    @Override
    public int refCnt() {
        return this.target.refCnt();
    }

    @Override
    public ByteBuf retain() {
        this.target.retain();
        return this;
    }

    @Override
    public ByteBuf retain(int increment) {
        this.target.retain(increment);
        return this;
    }

    @Override
    public boolean release() {
        return this.target.release();
    }

    @Override
    public boolean release(int decrement) {
        return this.target.release(decrement);
    }

    @Override
    public void discardReadBytes() {
        this.target.discardReadBytes();
    }

    @Override
    public ByteBuf sliceOff(int splitOffset) {
        return this.target.sliceOff(splitOffset);
    }

    @Override
    public void free() {
        this.target.free();
    }

    @Override
    public void close() throws IOException {
        this.target.close();
    }

    @Override
    public boolean isFree() {
        return this.target.isFree();
    }

    @Override
    public int readableBytes() {
        return this.target.readableBytes();
    }

    @Override
    public int readBytes() {
        return this.target.readBytes();
    }

    @Override
    public int writableBytes() {
        return this.target.writableBytes();
    }

    @Override
    public int writtenBytes() {
        return this.target.writtenBytes();
    }

    @Override
    public ByteBuf markReader() {
        return this.target.markReader();
    }

    @Override
    public ByteBuf markWriter() {
        return this.target.markWriter();
    }

    @Override
    public ByteBuf flush() throws IOException {
        return this.target.flush();
    }

    @Override
    public void clear() {
        this.target.clear();
    }

    @Override
    public ByteBuf resetReader() {
        return this.target.resetReader();
    }

    @Override
    public ByteBuf resetWriter() {
        return this.target.resetWriter();
    }

    @Override
    public ByteBuf skipReadableBytes(int length) {
        return this.target.skipReadableBytes(length);
    }

    @Override
    public ByteBuf skipWritableBytes(int length) {
        return this.target.skipWritableBytes(length);
    }

    @Override
    public void writeByte(byte n) {
        this.target.writeByte(n);
    }

    @Override
    public int writeBytes(byte[] src) {
        return this.target.writeBytes(src);
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        return this.target.writeBytes(src, off, len);
    }

    @Override
    public void writeInt16(short n) {
        this.target.writeInt16(n);
    }

    @Override
    public void writeInt24(int n) {
        this.target.writeInt24(n);
    }

    @Override
    public void writeInt32(int n) {
        this.target.writeInt32(n);
    }

    @Override
    public void writeUInt32(long n) {
        this.target.writeUInt32(n);
    }

    @Override
    public void writeInt64(long n) {
        this.target.writeInt64(n);
    }

    @Override
    public void writeFloat32(float n) {
        this.target.writeFloat32(n);
    }

    @Override
    public void writeFloat64(double n) {
        this.target.writeFloat64(n);
    }

    @Override
    public int writeBuffer(ByteBuffer src) {
        return this.target.writeBuffer(src);
    }

    @Override
    public int writeBuffer(ByteBuffer src, int len) {
        return this.target.writeBuffer(src, len);
    }

    @Override
    public int writeBuffer(ByteBuf src) {
        return this.target.writeBuffer(src);
    }

    @Override
    public int writeBuffer(ByteBuf src, int len) {
        return this.target.writeBuffer(src, len);
    }

    @Override
    public int writeString(String string, Charset charset) {
        return this.target.writeString(string, charset);
    }

    @Override
    public void setByte(int offset, byte n) {
        this.target.setByte(offset, n);
    }

    @Override
    public void setBytes(int offset, byte[] src) {
        this.target.setBytes(offset, src);
    }

    @Override
    public void setBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        this.target.setBytes(offset, src, srcOffset, srcLen);
    }

    @Override
    public void setInt16(int offset, short n) {
        this.target.setInt16(offset, n);
    }

    @Override
    public void setInt24(int offset, int n) {
        this.target.setInt24(offset, n);
    }

    @Override
    public void setInt32(int offset, int n) {
        this.target.setInt32(offset, n);
    }

    @Override
    public void setInt64(int offset, long n) {
        this.target.setInt64(offset, n);
    }

    @Override
    public void setFloat32(int offset, float n) {
        this.target.setFloat32(offset, n);
    }

    @Override
    public void setFloat64(int offset, double n) {
        this.target.setFloat64(offset, n);
    }

    @Override
    public int setBuffer(int offset, ByteBuffer src) {
        return this.target.setBuffer(offset, src);
    }

    @Override
    public int setBuffer(int offset, ByteBuffer src, int srcLen) {
        return this.target.setBuffer(offset, src, srcLen);
    }

    @Override
    public int setBuffer(int offset, ByteBuf src) {
        return this.target.setBuffer(offset, src);
    }

    @Override
    public int setBuffer(int offset, ByteBuf src, int srcLen) {
        return this.target.setBuffer(offset, src, srcLen);
    }

    @Override
    public int setString(int offset, String string, Charset charset) {
        return this.target.setString(offset, string, charset);
    }

    @Override
    public byte readByte() {
        return this.target.readByte();
    }

    @Override
    public int readBytes(byte[] dst) {
        return this.target.readBytes(dst);
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        return this.target.readBytes(dst, off, len);
    }

    @Override
    public short readInt16() {
        return this.target.readInt16();
    }

    @Override
    public int readInt24() {
        return this.target.readInt24();
    }

    @Override
    public int readInt32() {
        return this.target.readInt32();
    }

    @Override
    public long readInt64() {
        return this.target.readInt64();
    }

    @Override
    public float readFloat32() {
        return this.target.readFloat32();
    }

    @Override
    public double readFloat64() {
        return this.target.readFloat64();
    }

    @Override
    public int readBuffer(ByteBuffer dst) {
        return this.target.readBuffer(dst);
    }

    @Override
    public int readBuffer(ByteBuffer dst, int len) {
        return this.target.readBuffer(dst, len);
    }

    @Override
    public int readBuffer(ByteBuf dst) {
        return this.target.readBuffer(dst);
    }

    @Override
    public int readBuffer(ByteBuf dst, int len) {
        return this.target.readBuffer(dst, len);
    }

    @Override
    public String readString(int len, Charset charset) {
        return this.target.readString(len, charset);
    }

    @Override
    public byte getByte(int offset) {
        return this.target.getByte(offset);
    }

    @Override
    public int getBytes(int offset, byte[] dst) {
        return this.target.getBytes(offset, dst);
    }

    @Override
    public int getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        return this.target.getBytes(offset, dst, dstOffset, dstLen);
    }

    @Override
    public short getInt16(int offset) {
        return this.target.getInt16(offset);
    }

    @Override
    public int getInt24(int offset) {
        return this.target.getInt24(offset);
    }

    @Override
    public int getInt32(int offset) {
        return this.target.getInt32(offset);
    }

    @Override
    public long getInt64(int offset) {
        return this.target.getInt64(offset);
    }

    @Override
    public float getFloat32(int offset) {
        return this.target.getFloat32(offset);
    }

    @Override
    public double getFloat64(int offset) {
        return this.target.getFloat64(offset);
    }

    @Override
    public int getBuffer(int offset, ByteBuffer dst) {
        return this.target.getBuffer(offset, dst);
    }

    @Override
    public int getBuffer(int offset, ByteBuffer dst, int dstLen) {
        return this.target.getBuffer(offset, dst, dstLen);
    }

    @Override
    public int getBuffer(int offset, ByteBuf dst) {
        return this.target.getBuffer(offset, dst);
    }

    @Override
    public int getBuffer(int offset, ByteBuf dst, int dstLen) {
        return this.target.getBuffer(offset, dst, dstLen);
    }

    @Override
    public String getString(int offset, int len, Charset charset) {
        return this.target.getString(offset, len, charset);
    }

    @Override
    public short readUInt8() {
        return this.target.readUInt8();
    }

    @Override
    public int readUInt16() {
        return this.target.readUInt16();
    }

    @Override
    public int readUInt24() {
        return this.target.readUInt24();
    }

    @Override
    public long readUInt32() {
        return this.target.readUInt32();
    }

    @Override
    public short getUInt8(int offset) {
        return this.target.getUInt8(offset);
    }

    @Override
    public int getUInt16(int offset) {
        return this.target.getUInt16(offset);
    }

    @Override
    public int getUInt24(int offset) {
        return this.target.getUInt24(offset);
    }

    @Override
    public long getUInt32(int offset) {
        return this.target.getUInt32(offset);
    }

    @Override
    public int expect(String expect, Charset charset) {
        return this.target.expect(expect, charset);
    }

    @Override
    public int expectLine() {
        return this.target.expectLine();
    }

    @Override
    public boolean hasLine() {
        return this.target.hasLine();
    }

    @Override
    public String readLine() {
        return this.target.readLine();
    }

    @Override
    public String readLine(Charset charset) {
        return this.target.readLine(charset);
    }

    @Override
    public int expect(char expect, Charset charset) {
        return this.target.expect(expect, charset);
    }

    @Override
    public String readExpect(String expect, Charset charset) {
        return this.target.readExpect(expect, charset);
    }

    @Override
    public String readExpect(char expect, Charset charset) {
        return this.target.readExpect(expect, charset);
    }

    @Override
    public int expectLast(String expect, Charset charset) {
        return this.target.expectLast(expect, charset);
    }

    @Override
    public int expectLast(char expect, Charset charset) {
        return this.target.expectLast(expect, charset);
    }

    @Override
    public String readExpectLast(String expect, Charset charset) {
        return this.target.readExpectLast(expect, charset);
    }

    @Override
    public String readExpectLast(char expect, Charset charset) {
        return this.target.readExpectLast(expect, charset);
    }

    @Override
    public int read(ByteBuffer dst) {
        return this.target.read(dst);
    }

    @Override
    public int write(ByteBuffer src) {
        return this.target.write(src);
    }

    @Override
    public boolean isOpen() {
        return this.target.isOpen();
    }
}
