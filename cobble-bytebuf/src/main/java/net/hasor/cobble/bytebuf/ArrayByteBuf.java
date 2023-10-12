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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * 基于 字节数组的 ByteBuf 接口实现。
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public class ArrayByteBuf extends AbstractByteBuf {
    private byte[] data;

    ArrayByteBuf(ByteBufAllocator alloc, byte[] initData, int maxCapacity) {
        super(alloc, maxCapacity == -1 ? -1 : Math.max(initData.length, maxCapacity));
        this.data = initData;
        this.writerIndex = initData.length;
    }

    ArrayByteBuf(ByteBufAllocator alloc, int capacity, int maxCapacity) {
        super(alloc, maxCapacity);
        if (capacity < 0 || maxCapacity > 0) {
            if (!(0 < capacity && capacity <= maxCapacity)) {
                throw new IllegalArgumentException("0 > capacity > maxCapacity ( gt 0 or eq -1)");
            }
        }

        this.data = new byte[capacity];
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int capacity = this.data.length;
        if (offset < capacity) {
            this.data[offset] = b;
        } else {
            int off = offset - capacity;
            this.data[off] = b;
        }
    }

    @Override
    protected void _putBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int capacity = this.data.length;
        if (offset > capacity) {
            int cutOffset = offset - capacity;
            if (cutOffset > capacity) {
                throw new IndexOutOfBoundsException();
            }
            offset = cutOffset;
        }

        if ((offset + len) < capacity) {
            System.arraycopy(b, off, this.data, offset, len);
        } else {
            int partA = capacity - offset;
            int partB = len - partA;
            System.arraycopy(b, off, this.data, offset, partA);
            System.arraycopy(b, partA, this.data, 0, partB);
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int capacity = this.data.length;
        if (offset < capacity) {
            return this.data[offset];
        } else {
            int off = offset - capacity;
            return this.data[off];
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int capacity = this.data.length;
        if ((offset + len) < capacity) {
            System.arraycopy(this.data, offset, b, off, len);
            return len;
        } else {
            int partA = capacity - offset;
            int partB = len - partA;
            System.arraycopy(this.data, offset, b, off, partA);
            System.arraycopy(this.data, 0, b, off + partA, partB);
            return partA + partB;
        }
    }

    @Override
    protected void extendByteBuf(int targetCapacity) {
        checkFree();

        if (this.getMaxCapacity() > 0 && targetCapacity > this.getMaxCapacity()) {
            throw new BufferOverflowException();
        }

        if (targetCapacity > this.data.length) {
            byte[] newArray = new byte[targetCapacity];
            System.arraycopy(this.data, 0, newArray, 0, this.data.length);
            this.data = newArray;
        }
    }

    @Override
    protected void receivedBytes(int lastMarkedWriter, int currentMarkedWriter) {
        this.updateIndex();
    }

    @Override
    protected void recycleByteBuf() {
        this.updateIndex();
    }

    private void updateIndex() {
        int capacity = this.data.length;
        if (this.markedReaderIndex >= capacity) {
            this.markedReaderIndex = this.markedReaderIndex - capacity;
            this.markedWriterIndex = this.markedWriterIndex - capacity;
            this.readerIndex = this.readerIndex - capacity;
            this.writerIndex = this.writerIndex - capacity;
        }
    }

    @Override
    public int capacity() {
        checkFree();
        return this.data.length;
    }

    @Override
    public byte[] array() {
        return this.data;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public ArrayByteBuf copy() {
        checkFree();

        ArrayByteBuf copy = new ArrayByteBuf(this.alloc, this.capacity(), this.getMaxCapacity());
        copy.data = this.data.clone();

        return copy;
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(this.data);
    }

    @Override
    public void free() {
        if (this.isFree()) {
            return;
        }

        this.data = null;
        super.free();
    }
}