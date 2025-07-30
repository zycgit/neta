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

/**
 * readMark &lt;= readIndex &lt;= writerMark &lt;= writerIndex &lt;= capacity
 */
public class ByteBufAdapter implements ByteBuf {
    protected final ByteBuf byteBuf;

    public ByteBufAdapter(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;
    }

    @Override
    public ByteBufAllocator alloc() {
        return this.byteBuf.alloc();
    }

    @Override
    public int readerIndex() {
        return this.byteBuf.readerIndex();
    }

    @Override
    public int writerIndex() {
        return this.byteBuf.writerIndex();
    }

    @Override
    public int capacity() {
        return this.byteBuf.capacity();
    }

    @Override
    public byte[] asByteArray() {
        return this.byteBuf.asByteArray();
    }

    @Override
    public boolean isDirect() {
        return this.byteBuf.isDirect();
    }

    @Override
    public ByteBuf copy() {
        return this.byteBuf.copy();
    }

    //    @Override
    //    public ByteBuf asReadOnly() {
    //        return this.byteBuf.asReadOnly();
    //    }

    @Override
    public ByteOrder order() {
        return this.byteBuf.order();
    }

    @Override
    public ByteBuf order(ByteOrder newOrder) {
        return this.byteBuf.order(newOrder);
    }

    @Override
    public void free() {
        this.byteBuf.free();
    }

    @Override
    public void close() throws IOException {
        this.byteBuf.close();
    }

    @Override
    public boolean isFree() {
        return this.byteBuf.isFree();
    }

    @Override
    public int readableBytes() {
        return this.byteBuf.readableBytes();
    }

    @Override
    public int readBytes() {
        return this.byteBuf.readBytes();
    }

    @Override
    public int writableBytes() {
        return this.byteBuf.writableBytes();
    }

    @Override
    public int writtenBytes() {
        return this.byteBuf.writtenBytes();
    }

    @Override
    public ByteBuf markReader() {
        return this.byteBuf.markReader();
    }

    @Override
    public ByteBuf markWriter() {
        return this.byteBuf.markWriter();
    }

    @Override
    public ByteBuf flush() throws IOException {
        return this.byteBuf.flush();
    }

    @Override
    public void clear() {
        this.byteBuf.clear();
    }

    @Override
    public ByteBuf resetReader() {
        return this.byteBuf.resetReader();
    }

    @Override
    public ByteBuf resetWriter() {
        return this.byteBuf.resetWriter();
    }

    @Override
    public ByteBuf skipReadableBytes(int length) {
        return this.byteBuf.skipReadableBytes(length);
    }

    @Override
    public ByteBuf skipWritableBytes(int length) {
        return this.byteBuf.skipWritableBytes(length);
    }

    @Override
    public void writeByte(byte n) {
        this.byteBuf.writeByte(n);
    }

    @Override
    public int writeBytes(byte[] src) {
        return this.byteBuf.writeBytes(src);
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        return this.byteBuf.writeBytes(src, off, len);
    }

    @Override
    public void writeInt16(short n) {
        this.byteBuf.writeInt16(n);
    }

    @Override
    public void writeInt24(int n) {
        this.byteBuf.writeInt24(n);
    }

    @Override
    public void writeInt32(int n) {
        this.byteBuf.writeInt32(n);
    }

    @Override
    public void writeUInt32(long n) {
        this.byteBuf.writeUInt32(n);
    }

    @Override
    public void writeInt64(long n) {
        this.byteBuf.writeInt64(n);
    }

    @Override
    public void writeFloat32(float n) {
        this.byteBuf.writeFloat32(n);
    }

    @Override
    public void writeFloat64(double n) {
        this.byteBuf.writeFloat64(n);
    }

    @Override
    public int writeBuffer(ByteBuffer src) {
        return this.byteBuf.writeBuffer(src);
    }

    @Override
    public int writeBuffer(ByteBuffer src, int len) {
        return this.byteBuf.writeBuffer(src, len);
    }

    @Override
    public int writeBuffer(ByteBuf src) {
        return this.byteBuf.writeBuffer(src);
    }

    @Override
    public int writeBuffer(ByteBuf src, int len) {
        return this.byteBuf.writeBuffer(src, len);
    }

    @Override
    public int writeString(String string, Charset charset) {
        return this.byteBuf.writeString(string, charset);
    }

    @Override
    public void setByte(int offset, byte n) {
        this.byteBuf.setByte(offset, n);
    }

    @Override
    public void setBytes(int offset, byte[] src) {
        this.byteBuf.setBytes(offset, src);
    }

    @Override
    public void setBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        this.byteBuf.setBytes(offset, src, srcOffset, srcLen);
    }

    @Override
    public void setInt16(int offset, short n) {
        this.byteBuf.setInt16(offset, n);
    }

    @Override
    public void setInt24(int offset, int n) {
        this.byteBuf.setInt24(offset, n);
    }

    @Override
    public void setInt32(int offset, int n) {
        this.byteBuf.setInt32(offset, n);
    }

    @Override
    public void setInt64(int offset, long n) {
        this.byteBuf.setInt64(offset, n);
    }

    @Override
    public void setFloat32(int offset, float n) {
        this.byteBuf.setFloat32(offset, n);
    }

    @Override
    public void setFloat64(int offset, double n) {
        this.byteBuf.setFloat64(offset, n);
    }

    @Override
    public int setBuffer(int offset, ByteBuffer src) {
        return this.byteBuf.setBuffer(offset, src);
    }

    @Override
    public int setBuffer(int offset, ByteBuffer src, int srcLen) {
        return this.byteBuf.setBuffer(offset, src, srcLen);
    }

    @Override
    public int setBuffer(int offset, ByteBuf src) {
        return this.byteBuf.setBuffer(offset, src);
    }

    @Override
    public int setBuffer(int offset, ByteBuf src, int srcLen) {
        return this.byteBuf.setBuffer(offset, src, srcLen);
    }

    @Override
    public int setString(int offset, String string, Charset charset) {
        return this.byteBuf.setString(offset, string, charset);
    }

    @Override
    public byte readByte() {
        return this.byteBuf.readByte();
    }

    @Override
    public int readBytes(byte[] dst) {
        return this.byteBuf.readBytes(dst);
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        return this.byteBuf.readBytes(dst, off, len);
    }

    @Override
    public short readInt16() {
        return this.byteBuf.readInt16();
    }

    @Override
    public int readInt24() {
        return this.byteBuf.readInt24();
    }

    @Override
    public int readInt32() {
        return this.byteBuf.readInt32();
    }

    @Override
    public long readInt64() {
        return this.byteBuf.readInt64();
    }

    @Override
    public float readFloat32() {
        return this.byteBuf.readFloat32();
    }

    @Override
    public double readFloat64() {
        return this.byteBuf.readFloat64();
    }

    @Override
    public int readBuffer(ByteBuffer dst) {
        return this.byteBuf.readBuffer(dst);
    }

    @Override
    public int readBuffer(ByteBuffer dst, int len) {
        return this.byteBuf.readBuffer(dst, len);
    }

    @Override
    public int readBuffer(ByteBuf dst) {
        return this.byteBuf.readBuffer(dst);
    }

    @Override
    public int readBuffer(ByteBuf dst, int len) {
        return this.byteBuf.readBuffer(dst, len);
    }

    @Override
    public String readString(int len, Charset charset) {
        return this.byteBuf.readString(len, charset);
    }

    @Override
    public byte getByte(int offset) {
        return this.byteBuf.getByte(offset);
    }

    @Override
    public int getBytes(int offset, byte[] dst) {
        return this.byteBuf.getBytes(offset, dst);
    }

    @Override
    public int getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        return this.byteBuf.getBytes(offset, dst, dstOffset, dstLen);
    }

    @Override
    public short getInt16(int offset) {
        return this.byteBuf.getInt16(offset);
    }

    @Override
    public int getInt24(int offset) {
        return this.byteBuf.getInt24(offset);
    }

    @Override
    public int getInt32(int offset) {
        return this.byteBuf.getInt32(offset);
    }

    @Override
    public long getInt64(int offset) {
        return this.byteBuf.getInt64(offset);
    }

    @Override
    public float getFloat32(int offset) {
        return this.byteBuf.getFloat32(offset);
    }

    @Override
    public double getFloat64(int offset) {
        return this.byteBuf.getFloat64(offset);
    }

    @Override
    public int getBuffer(int offset, ByteBuffer dst) {
        return this.byteBuf.getBuffer(offset, dst);
    }

    @Override
    public int getBuffer(int offset, ByteBuffer dst, int dstLen) {
        return this.byteBuf.getBuffer(offset, dst, dstLen);
    }

    @Override
    public int getBuffer(int offset, ByteBuf dst) {
        return this.byteBuf.getBuffer(offset, dst);
    }

    @Override
    public int getBuffer(int offset, ByteBuf dst, int dstLen) {
        return this.byteBuf.getBuffer(offset, dst, dstLen);
    }

    @Override
    public String getString(int offset, int len, Charset charset) {
        return this.byteBuf.getString(offset, len, charset);
    }

    @Override
    public short readUInt8() {
        return this.byteBuf.readUInt8();
    }

    @Override
    public int readUInt16() {
        return this.byteBuf.readUInt16();
    }

    @Override
    public int readUInt24() {
        return this.byteBuf.readUInt24();
    }

    @Override
    public long readUInt32() {
        return this.byteBuf.readUInt32();
    }

    @Override
    public short getUInt8(int offset) {
        return this.byteBuf.getUInt8(offset);
    }

    @Override
    public int getUInt16(int offset) {
        return this.byteBuf.getUInt16(offset);
    }

    @Override
    public int getUInt24(int offset) {
        return this.byteBuf.getUInt24(offset);
    }

    @Override
    public long getUInt32(int offset) {
        return this.byteBuf.getUInt32(offset);
    }

    @Override
    public int expect(String expect, Charset charset) {
        return this.byteBuf.expect(expect, charset);
    }

    @Override
    public int expectLine() {
        return this.byteBuf.expectLine();
    }

    @Override
    public boolean hasLine() {
        return this.byteBuf.hasLine();
    }

    @Override
    public String readLine() {
        return this.byteBuf.readLine();
    }

    @Override
    public String readLine(Charset charset) {
        return this.byteBuf.readLine(charset);
    }

    @Override
    public int expect(char expect, Charset charset) {
        return this.byteBuf.expect(expect, charset);
    }

    @Override
    public String readExpect(String expect, Charset charset) {
        return this.byteBuf.readExpect(expect, charset);
    }

    @Override
    public String readExpect(char expect, Charset charset) {
        return this.byteBuf.readExpect(expect, charset);
    }

    @Override
    public int expectLast(String expect, Charset charset) {
        return this.byteBuf.expectLast(expect, charset);
    }

    @Override
    public int expectLast(char expect, Charset charset) {
        return this.byteBuf.expectLast(expect, charset);
    }

    @Override
    public String readExpectLast(String expect, Charset charset) {
        return this.byteBuf.readExpectLast(expect, charset);
    }

    @Override
    public String readExpectLast(char expect, Charset charset) {
        return this.byteBuf.readExpectLast(expect, charset);
    }

    @Override
    public int read(ByteBuffer dst) {
        return this.byteBuf.read(dst);
    }

    @Override
    public int write(ByteBuffer src) {
        return this.byteBuf.write(src);
    }

    @Override
    public boolean isOpen() {
        return this.byteBuf.isOpen();
    }
}
