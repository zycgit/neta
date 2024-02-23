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
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
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
    public byte[] array() {
        return this.byteBuf.array();
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
    public ByteBuffer asByteBuffer() {
        return this.byteBuf.asByteBuffer();
    }

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
    public boolean hasReadable() {
        return this.byteBuf.hasReadable();
    }

    @Override
    public int writableBytes() {
        return this.byteBuf.writableBytes();
    }

    @Override
    public int writedBytes() {
        return this.byteBuf.writedBytes();
    }

    @Override
    public boolean hasWritable() {
        return this.byteBuf.hasWritable();
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
    public void writeBytes(byte[] b) {
        this.byteBuf.writeBytes(b);
    }

    @Override
    public void writeBytes(byte[] b, int off, int len) {
        this.byteBuf.writeBytes(b, off, len);
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
    public int write(ByteBuffer src) {
        return this.byteBuf.write(src);
    }

    @Override
    public int write(ByteBuf src) {
        return this.byteBuf.write(src);
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
    public void setBytes(int offset, byte[] b) {
        this.byteBuf.setBytes(offset, b);
    }

    @Override
    public void setBytes(int offset, byte[] b, int off, int len) {
        this.byteBuf.setBytes(offset, b, off, len);
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
    public int setString(int offset, String string, Charset charset) {
        return this.byteBuf.setString(offset, string, charset);
    }

    @Override
    public byte readByte() {
        return this.byteBuf.readByte();
    }

    @Override
    public int readBytes(byte[] b) {
        return this.byteBuf.readBytes(b);
    }

    @Override
    public int readBytes(byte[] b, int off, int len) {
        return this.byteBuf.readBytes(b, off, len);
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
    public int read(ByteBuffer dst) {
        return this.byteBuf.read(dst);
    }

    @Override
    public int read(ByteBuf dst) {
        return this.byteBuf.read(dst);
    }

    @Override
    public int read(ByteBuf dst, int len) {
        return this.byteBuf.read(dst, len);
    }

    //    @Override
    //    public int read(ByteBuf dest, int off, int len) {
    //        return this.byteBuf.read(dest, off, len);
    //    }

    @Override
    public String readString(int len, Charset charset) {
        return this.byteBuf.readString(len, charset);
    }

    @Override
    public byte getByte(int offset) {
        return this.byteBuf.getByte(offset);
    }

    @Override
    public int getBytes(int offset, byte[] b) {
        return this.byteBuf.getBytes(offset, b);
    }

    @Override
    public int getBytes(int offset, byte[] b, int off, int len) {
        return this.byteBuf.getBytes(offset, b, off, len);
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
    public boolean isOpen() {
        return this.byteBuf.isOpen();
    }

    @Override
    public void close() throws IOException {
        this.byteBuf.close();
    }
}
