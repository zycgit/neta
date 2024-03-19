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
import net.hasor.cobble.function.EFunction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * <pre>
 * +----------+-------------+------------+-------------+----------+
 * | ancient  | discardable | readable   | overlayable | writable |
 * +------------------------+------------+-------------+----------+
 * |          |             |            |             |          |
 * 0   ≤   marked  ≤  readerIndex  ≤  marked  ≤  writerIndex ≤ capacity
 *      readerIndex                writerIndex
 *
 * </pre>
 */
public interface ByteBuf extends ByteChannel {
    /** Returns the {@link ByteBufAllocator} which created this buffer. */
    ByteBufAllocator alloc();

    /** Returns the {@code readerIndex} of this buffer. */
    int readerIndex();

    /** Returns the {@code writerIndex} of this buffer. */
    int writerIndex();

    /** 最大限制 */
    int capacity();

    /** ByteBuf 的字节数组形态 */
    byte[] asByteArray();

    /** ByteBuf 是否为堆外方式 */
    boolean isDirect();

    /** 复制个 ByteBuf , 连同 buffer 的数据一起复制 */
    ByteBuf copy();

    //    /** 返回只读模式的 ByteBuf。只读模式的 如果底层 */
    //    ByteBuf asReadOnly();

    /** 返回 ByteBuffer 形态，PooledNioByteBuf 不支持该方法 */
    @Deprecated
    default ByteBuffer asByteBuffer() {
        throw new UnsupportedOperationException();
    }

    /** 字节序 */
    ByteOrder order();

    /** 设置字节序 */
    ByteBuf order(ByteOrder newOrder);

    /** 释放 Buffer 占用的内存 */
    void free();

    default void close() throws IOException {
        this.free();
    }

    /** 是否已经释放 */
    boolean isFree();

    /**
     * Returns the number of readable bytes which is equal to
     * {@code (this.markedWriterIndex - this.readerIndex)}.
     */
    int readableBytes();

    /**
     * Returns the number of read bytes which is equal to
     * {@code (readerIndex - markedReaderIndex)}.
     */
    int readBytes();

    /**
     * Returns the number of writable bytes which is equal to
     * {@code (maxCapacity - (writerIndex - markedReaderIndex))}.
     */
    int writableBytes();

    /**
     * Returns the number of Written bytes which is equal to
     * {@code (writerIndex - markedWriterIndex)}.
     */
    int writtenBytes();

    /**
     * Marks the current {@code readerIndex} in this buffer.
     * You can reposition the current {@code readerIndex} to the marked {@code readerIndex} by calling {@link #resetReader()}.
     * The initial value of the marked {@code readerIndex} is {@code 0}.
     */
    ByteBuf markReader();

    /**
     * Marks the current {@code writerIndex} in this buffer.
     * You can reposition the current {@code writerIndex} to the marked {@code writerIndex} by calling {@link #resetWriter()}.
     * The initial value of the marked {@code writerIndex} is {@code 0}.
     */
    ByteBuf markWriter();

    /** same as markWriter() */
    default ByteBuf flush() throws IOException {
        return markWriter();
    }

    /** reset the markWriter, and skip all readable data */
    default void clear() {
        this.resetWriter();
        this.skipReadableBytes(this.readableBytes());
        this.markReader();
    }

    /**
     * Repositions the current {@code readerIndex} to the marked
     * {@code readerIndex} in this buffer.
     * @throws IndexOutOfBoundsException if the current {@code writerIndex} is less than the marked {@code readerIndex}
     */
    ByteBuf resetReader();

    /**
     * Repositions the current {@code writerIndex} to the marked
     * {@code writerIndex} in this buffer.
     * @throws IndexOutOfBoundsException if the current {@code readerIndex} is greater than the marked {@code writerIndex}
     */
    ByteBuf resetWriter();

    ByteBuf skipReadableBytes(int length);

    ByteBuf skipWritableBytes(int length);

    /**
     * 写入 1 字节的 byte，写入后 writerIndex 会 + 1。
     * 如果 writerIndex + 1 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeByte(byte n);

    /**
     * 数据写入，写入后 writerIndex 会增加 src.length。
     * 如果 writerIndex + src.length > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    default void writeBytes(byte[] src) {
        this.writeBytes(src, 0, src.length);
    }

    /**
     * 数据写入，写入后 writerIndex 会增加 len。
     * 如果 writerIndex + len > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    int writeBytes(byte[] src, int off, int len);

    /**
     * 写入 2 字节的 sort（大端字节序），写入后 writerIndex 会 + 2。
     * 如果 writerIndex + 2 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeInt16(short n);

    /**
     * 写入 3 字节的 int（大端字节序），写入后 writerIndex 会 + 3。
     * 如果 writerIndex + 3 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeInt24(int n);

    /**
     * 写入 4 字节的 int（大端字节序），写入后 writerIndex 会 + 4。
     * 如果 writerIndex + 4 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeInt32(int n);

    /**
     * 写入 4 字节的无符号 int（大端字节序），写入后 writerIndex 会 + 4。
     * 如果 writerIndex + 4 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeUInt32(long n);

    /**
     * 写入 8 字节的 long（大端字节序），写入后 writerIndex 会 + 8。
     * 如果 writerIndex + 8 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeInt64(long n);

    /**
     * 写入 4 字节的 float（大端字节序），写入后 writerIndex 会 + 4。
     * 如果 writerIndex + 4 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeFloat32(float n);

    /**
     * 写入 8 字节的 double（大端字节序），写入后 writerIndex 会 + 8。
     * 如果 writerIndex + 8 > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void writeFloat64(double n);

    default int writeBuffer(ByteBuffer src) {
        return this.writeBuffer(src, 0, src.remaining());
    }

    default int writeBuffer(ByteBuffer src, int len) {
        return this.writeBuffer(src, 0, len);
    }

    int writeBuffer(ByteBuffer src, int off, int len);

    default int writeBuffer(ByteBuf src) {
        return this.writeBuffer(src, 0, src.readableBytes());
    }

    default int writeBuffer(ByteBuf src, int len) {
        return this.writeBuffer(src, 0, len);
    }

    int writeBuffer(ByteBuf src, int off, int len);

    /**
     * 字符串会以 str.getBytes(charset) 方式转换为字节数组并写入缓存。返回值是写入的字节数。
     * 如果 writerIndex + [string 字节数组长度] > capacity 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    default int writeString(String string, Charset charset) {
        if (string != null && !string.equals("")) {
            byte[] bytes = string.getBytes(charset);
            writeBytes(bytes);
            return bytes.length;
        } else {
            return 0;
        }
    }

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 1 字节的 byte，该方法不会更新 writerIndex 值。
     * 参数 offset + 1 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setByte(int offset, byte n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 src 数组的数据，该方法不会更新 writerIndex 值。
     * 参数 offset + src.length 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setBytes(int offset, byte[] src);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 src 数组的数据，该方法不会更新 writerIndex 值。
     * 参数 offset + srcLen 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setBytes(int offset, byte[] src, int srcOffset, int srcLen);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 2 字节长度的 sort（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 2 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setInt16(int offset, short n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 3 字节长度的 int（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 3 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setInt24(int offset, int n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 4 字节长度的 int（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 4 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setInt32(int offset, int n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 8 字节长度的 long（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 8 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setInt64(int offset, long n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 4 字节长度的 float（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 4 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setFloat32(int offset, float n);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入 8 字节长度的 float（大端字节序），该方法不会更新 writerIndex 值。
     * 参数 offset + 8 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    void setFloat64(int offset, double n);

    default int setBuffer(int offset, ByteBuffer src) {
        return this.setBuffer(offset, src, 0, src.remaining());
    }

    int setBuffer(int offset, ByteBuffer src, int srcOffset, int srcLen);

    default int setBuffer(int offset, ByteBuf src) {
        return this.setBuffer(offset, src, 0, src.readableBytes());
    }

    int setBuffer(int offset, ByteBuf src, int srcOffset, int srcLen);

    /**
     * 在 offset 偏移量的位置上向后覆盖方式写入字符串，字符串会通过 str.getBytes(charset) 方式转换为字节数组，该方法不会更新 writerIndex 值。返回值是写入了多少个字节。
     * 参数 offset + [string 字节数组长度] 必须要小于 writerIndex，否则会引发 {@link IndexOutOfBoundsException} 异常
     */
    default int setString(int offset, String string, Charset charset) {
        if (string != null && !string.equals("")) {
            byte[] bytes = string.getBytes(charset);
            setBytes(offset, bytes);
            return bytes.length;
        } else {
            return 0;
        }
    }

    /**
     * 读取 1 字节。
     * 如果 readableBytes() < 1 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    byte readByte();

    /** 读取一定数量的字节，并将它们存储到缓冲区数组 dst 中。实际读取的字节数以整数形式返回。如果读取到末尾或者没有可读的数据将会返回 -1。 */
    default int readBytes(byte[] dst) {
        return this.readBytes(dst, 0, dst.length);
    }

    /** 读取 len 数量的字节，并将它们存储到 off 位置开始的缓冲区数组 dst 中。实际读取的字节数以整数形式返回。如果读取到末尾或者没有可读的数据将会返回 -1。 */
    int readBytes(byte[] dst, int off, int len);

    /**
     * 读取 2 字节的 short（大端字节序），读取后 readerIndex 会增加 2。
     * 如果 readableBytes() < 2 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    short readInt16();

    /**
     * 读取 3 字节的 int（大端字节序），读取后 readerIndex 会增加 3。
     * 如果 readableBytes() < 3 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    int readInt24();

    /**
     * 读取 4 字节的 int（大端字节序），读取后 readerIndex 会增加 4。
     * 如果 readableBytes() < 4 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    int readInt32();

    /**
     * 读取 8 字节的 long（大端字节序），读取后 readerIndex 会增加 8。
     * 如果 readableBytes() < 8 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    long readInt64();

    /**
     * 读取 4 字节的 float（大端字节序），读取后 readerIndex 会增加 4。
     * 如果 readableBytes() < 4 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    float readFloat32();

    /**
     * 读取 8 字节的 double（大端字节序），读取后 readerIndex 会增加 8。
     * 如果 readableBytes() < 8 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    double readFloat64();

    /** use copy to dst */
    default int readBuffer(ByteBuffer dst) {
        return this.readBuffer(dst, 0, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** use copy to dst */
    default int readBuffer(ByteBuffer dst, int len) {
        return this.readBuffer(dst, 0, len);
    }

    /** use copy to dst */
    int readBuffer(ByteBuffer dst, int off, int len);

    /** use copy to dst */
    default int readBuffer(ByteBuf dst) {
        return this.readBuffer(dst, 0, Math.min(dst.writableBytes(), this.readableBytes()));
    }

    /** use copy to dst */
    default int readBuffer(ByteBuf dst, int len) {
        return this.readBuffer(dst, 0, len);
    }

    /** use copy to dst */
    int readBuffer(ByteBuf dst, int off, int len);

    /**
     * 读取 len 字节并将其构造成 String，读取后 readerIndex 会增加 len。
     * 如果 readableBytes() < len 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    default String readString(int len, Charset charset) {
        if (len == 0) {
            return "";
        }

        byte[] b = new byte[len];
        int readBytes = this.readBytes(b);
        if (charset == StandardCharsets.US_ASCII) {
            return new String(b, 0, readBytes);
        } else {
            return new String(b, 0, readBytes, charset);
        }
    }

    /**
     * 从 offset 偏移量的位置上开始读取 1 字节。
     * 若 offset + 1 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    byte getByte(int offset);

    /** 从 offset 偏移量的位置上开始读取一定数量的字节，并将它们存储到缓冲区数组 dst 中。实际读取的字节数以整数形式返回。如果读取到末尾或者没有可读的数据将会返回 -1 */
    default int getBytes(int offset, byte[] dst) {
        return getBytes(offset, dst, 0, dst.length);
    }

    /** 从 offset 偏移量的位置上开始读取 dstLen 数量的字节，并将它们存储到 dstOffset 位置开始的缓冲区数组 dst 中。实际读取的字节数以整数形式返回。如果读取到末尾或者没有可读的数据将会返回 -1 */
    int getBytes(int offset, byte[] dst, int dstOffset, int dstLen);

    /**
     * 读取 2 字节的 short（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 2 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    short getInt16(int offset);

    /**
     * 读取 3 字节的 int（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 3 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    int getInt24(int offset);

    /**
     * 读取 4 字节的 int（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 4 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    int getInt32(int offset);

    /**
     * 读取 8 字节的 long（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 8 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    long getInt64(int offset);

    /**
     * 读取 4 字节的 float（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 4 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    float getFloat32(int offset);

    /**
     * 读取 8 字节的 double（大端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 8 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    double getFloat64(int offset);

    /** use copy to dst */
    default int getBuffer(int offset, ByteBuffer dst) {
        return this.getBuffer(offset, dst, 0, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** use copy to dst */
    int getBuffer(int offset, ByteBuffer dst, int dstOffset, int dstLen);

    /** use copy to dst */
    default int getBuffer(int offset, ByteBuf dst) {
        return this.getBuffer(offset, dst, 0, Math.min(dst.writableBytes(), this.readableBytes()));
    }

    /** use copy to dst */
    int getBuffer(int offset, ByteBuf dst, int dstOffset, int dstLen);

    /**
     * 从 offset 开始读取 len 个字节，并构造一个 String，该方法不会更新 readerIndex 值。
     * 若 offset + len > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    default String getString(int offset, int len, Charset charset) {
        if (len == 0) {
            return "";
        }

        byte[] b = new byte[len];
        int readBytes = this.getBytes(offset, b);
        if (charset == StandardCharsets.US_ASCII) {
            return new String(b, 0, readBytes);
        } else {
            return new String(b, 0, readBytes, charset);
        }
    }

    /**
     * 读取 1 字节的 byte 返回 0～255 之间的一个数（大端字节序），读取后 readerIndex 会增加 1。
     * 如果 readableBytes() < 1 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    short readUInt8();

    /**
     * 读取 2 字节的 无符号 sort（大端字节序），读取后 readerIndex 会增加 2。
     * 如果 readableBytes() < 2 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    int readUInt16();

    /**
     * 读取 3 字节的 无符号 int（大端字节序），读取后 readerIndex 会增加 3。
     * 如果 readableBytes() < 3 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    int readUInt24();

    /**
     * 读取 4 字节的 无符号 int（大端字节序），读取后 readerIndex 会增加 4。
     * 如果 readableBytes() < 4 则会引发 {@link IndexOutOfBoundsException} 异常
     */
    long readUInt32();

    /**
     * 读取 1 字节的 byte 返回 0～255 之间的一个数（小端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 1 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    short getUInt8(int offset);

    /**
     * 读取 2 字节的 无符号 sort（小端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 2 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    int getUInt16(int offset);

    /**
     * 读取 3 字节的 无符号 int（小端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 3 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    int getUInt24(int offset);

    /**
     * 读取 4 字节的 无符号 int（小端字节序），该方法不会更新 readerIndex 值。
     * 若 offset + 4 > readableBytes() 那么将会引发 {@link IndexOutOfBoundsException} 异常
     */
    long getUInt32(int offset);

    /** 查找下一个 expect 字符串的出现的位置（使用指定的编码），该方法不会更新 readerIndex 值。如果不存在期待的字符串，那么返回 -1。 */
    default int expect(String expect, Charset charset) {
        int len = expect.getBytes(charset).length;
        int readableBytes = this.readableBytes();

        if (readableBytes >= len) {
            int loopCount = readableBytes - len;
            for (int i = 0; i <= loopCount; i++) {
                String dat = this.getString(i, len, charset);
                if (dat.equals(expect)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 查找最近的一个 '\n'，该方法不会更新 readerIndex 值。如果不存在期待的字符串，那么返回 -1 */
    default int expectLine() {
        int available = this.readableBytes();
        if (available == 0) {
            return -1;
        }

        int findIndex = -1;
        for (int i = 0; i < available; i++) {
            if (this.getUInt8(i) == '\n') {
                if (i > 0 && this.getUInt8(i - 1) == '\r') {
                    findIndex = i - 1;
                } else {
                    findIndex = i;
                }
                break;
            }
        }

        return findIndex;
    }

    /** 具有行结尾 */
    default boolean hasLine() {
        return expectLine() >= 0;
    }

    /** 读一整行 */
    default String readLine() {
        return this.readLine(StandardCharsets.US_ASCII);
    }

    /** 读一整行 */
    default String readLine(Charset charset) {
        int available = this.readableBytes();
        if (available == 0) {
            return null;
        }

        int findIndex = -1;
        int skipLength = -1;
        for (int i = 0; i < available; i++) {
            if (this.getUInt8(i) == '\n') {
                if (i > 0 && this.getUInt8(i - 1) == '\r') {
                    findIndex = i - 1;
                    skipLength = 2;
                } else {
                    findIndex = i;
                    skipLength = 1;
                }
                break;
            }
        }

        if (findIndex >= 0) {
            String str = this.readString(findIndex, charset);
            this.skipReadableBytes(skipLength);
            return str;
        } else {
            return null;
        }
    }

    /** 查找下一个 expect 字符的出现的位置（使用指定的编码），该方法不会更新 readerIndex 值。如果不存在期待的字符，那么返回 -1。 */
    default int expect(char expect, Charset charset) {
        return expect(String.valueOf(expect), charset);
    }

    /**
     * 从当前位置开始读取，直到遇到第一个 expect 字符串读完。如果没有期待的 expect 字符串那么返回 null。
     * 比如：readLine 可以写作 readExpectString("\n", StandardCharsets.US_ASCII)
     */
    default String readExpect(String expect, Charset charset) {
        int readLen;
        if ((readLen = this.expect(expect, charset)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expect.getBytes(charset).length);
            return str;
        } else {
            return null;
        }
    }

    /**
     * 从当前位置开始读取，直到遇到第一个 expect 字符串读完。如果没有期待的 expect 字符串那么返回 null。
     * 比如：readLine 可以写作 readExpectString('\n', StandardCharsets.US_ASCII)
     */
    default String readExpect(char expect, Charset charset) {
        return readExpect(String.valueOf(expect), charset);
    }

    /** 查找最后一个 expect 字符串的出现的位置（使用指定的编码），该方法不会更新 readerIndex 值。如果不存在期待的字符串，那么返回 -1。 */
    default int expectLast(String expect, Charset charset) {
        int len = expect.getBytes(charset).length;
        int readableBytes = this.readableBytes();

        if (readableBytes >= len) {
            int loopCount = readableBytes - len;
            for (int i = loopCount; i >= 0; i--) {
                String dat = this.getString(i, len, charset);
                if (dat.equals(expect)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 查找最后一个 expect 字符的出现的位置（使用指定的编码），该方法不会更新 readerIndex 值。如果不存在期待的字符，那么返回 -1。 */
    default int expectLast(char expect, Charset charset) {
        return expectLast(String.valueOf(expect), charset);
    }

    /**
     * 从当前位置开始读取，直到遇到最后一个 expect 字符串读完。如果没有期待的 expect 字符串那么返回 null。
     * 比如：readLine 可以写作 readExpectString("\n", StandardCharsets.US_ASCII)
     */
    default String readExpectLast(String expect, Charset charset) {
        int readLen = -1;
        if ((readLen = this.expectLast(expect, charset)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expect.getBytes(charset).length);
            return str;
        } else {
            return null;
        }
    }

    /**
     * 从当前位置开始读取，直到遇到最后一个 expect 字符串读完。如果没有期待的 expect 字符串那么返回 null。
     * 比如：readLine 可以写作 readExpectString('\n', StandardCharsets.US_ASCII)
     */
    default String readExpectLast(char expect, Charset charset) {
        return readExpectLast(String.valueOf(expect), charset);
    }

    /**
     * 期待可以对 Buffer 进行读操作。如果缓冲区中有数据可供读，方法会立刻返回否则会进入线程等待状态。
     * 在多线程并发读场景下 {@link #waitReadable()} 只能保证一个线程可以读取到数据；若需所有线程都能安全的读需要使用 {@link #waitReadable(EFunction)} 方法
     */
    default void waitReadable() throws InterruptedException, IOException {
        this.waitReadable(buf -> buf);
    }

    /**
     * 当缓冲区中有数据可供读时，方法会立刻调用 callBack。
     * 在多线程并发读场景下，读取线程会逐个进入 callBack，期间若没有足够的数据读取会阻塞后续线程
     */
    <T> T waitReadable(EFunction<ByteBuf, T, IOException> callBack) throws InterruptedException, IOException;

    /**
     * 期待可以对 Buffer 进行写操作。如果缓冲区中有数据可供写，方法会立刻返回否则会进入线程等待状态。
     * 在多线程并发写场景下 {@link #waitWriteable()} 只能保证一个线程可以写数据；若需所有线程都能安全的写需要使用 {@link #waitWriteable(EFunction)} 方法
     */
    default void waitWriteable() throws InterruptedException, IOException {
        this.waitWriteable(buf -> buf);
    }

    /**
     * 当缓冲区中可供写时，方法会立刻调用 callBack。
     * 在多线程并发写场景下，写线程会逐个进入 callBack，期间若没有足够的空间进行写入则会阻塞后续写线程
     */
    <T> T waitWriteable(EFunction<ByteBuf, T, IOException> callBack) throws InterruptedException, IOException;

    /** 等待读写 IO 锁 */
    <T> T waitLock(EFunction<ByteBuf, T, IOException> callBack) throws IOException;

    /** use copy to dst, implements {@link ReadableByteChannel} */
    default int read(ByteBuffer dst) {
        return this.readBuffer(dst, 0, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** use copy from src, implements {@link WritableByteChannel} */
    default int write(ByteBuffer src) {
        return this.writeBuffer(src, 0, src.remaining());
    }
}
