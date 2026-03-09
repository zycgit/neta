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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;

/**
 * Read-only {@link ByteBuf} view that delegates all read operations to another
 * buffer and rejects every write attempt.
 * <p>The wrapped buffer storage and reference-count lifecycle are shared; only
 * mutation APIs are blocked by throwing {@link ReadOnlyBufferException}.
 */
public class ReadOnlyByteBuf extends ByteBufProxy {

    ReadOnlyByteBuf(ByteBuf target) {
        super(target);
    }

    /** Returns this instance because it is already read-only. */
    public ByteBuf asReadOnly() {
        return this;
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeByte(byte n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBytes(byte[] src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBytes(byte[] src, int off, int len) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeInt16(short n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeInt24(int n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeInt32(int n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeUInt32(long n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeInt64(long n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeFloat32(float n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void writeFloat64(double n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBuffer(ByteBuffer src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBuffer(ByteBuffer src, int len) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBuffer(ByteBuf src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeBuffer(ByteBuf src, int len) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int writeString(String string, Charset charset) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setByte(int offset, byte n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setBytes(int offset, byte[] src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setInt16(int offset, short n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setInt24(int offset, int n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setInt32(int offset, int n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setInt64(int offset, long n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setFloat32(int offset, float n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void setFloat64(int offset, double n) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int setBuffer(int offset, ByteBuffer src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int setBuffer(int offset, ByteBuffer src, int srcLen) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int setBuffer(int offset, ByteBuf src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int setBuffer(int offset, ByteBuf src, int srcLen) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int setString(int offset, String string, Charset charset) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public int write(ByteBuffer src) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf skipWritableBytes(int length) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void discardReadBytes() {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf sliceOff(int splitOffset) {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public void clear() {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf markWriter() {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf resetWriter() {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf flush() {
        throw new ReadOnlyBufferException();
    }

    /** Always throws ReadOnlyBufferException. */
    @Override
    public ByteBuf order(ByteOrder newOrder) {
        throw new ReadOnlyBufferException();
    }

    /** Returns zero because the buffer cannot accept writes. */
    @Override
    public int writableBytes() {
        return 0;
    }

    /** Returns zero because this wrapper never records writes. */
    @Override
    public int writtenBytes() {
        return 0;
    }
}
