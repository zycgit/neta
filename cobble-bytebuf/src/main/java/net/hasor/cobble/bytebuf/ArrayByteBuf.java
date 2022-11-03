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

public class ArrayByteBuf extends AbstractByteBuf {
    private byte[]  data;
    private boolean isFree;

    public ArrayByteBuf(byte[] initData, int maxCapacity) {
        super(maxCapacity == -1 ? -1 : Math.max(initData.length, maxCapacity));
        this.data = initData;
        this.isFree = false;
    }

    public ArrayByteBuf(int initialCapacity, int maxCapacity) {
        super(maxCapacity);
        this.data = new byte[initialCapacity];
        this.isFree = false;
    }

    public byte[] array() {
        return this.data;
    }

    private void checkFree() {
        if (this.isFree) {
            throw new IllegalStateException("ByteBuf Has been released.");
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = (markedReaderIndex + offset) % this.data.length;
            this.data[baseOffset] = b;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected void _putBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = markedReaderIndex % this.data.length;
            if ((baseOffset + offset + len) > this.data.length) {
                int partA = this.data.length - (baseOffset + offset);
                int partB = len - partA;
                System.arraycopy(b, off, this.data, baseOffset, partA);
                System.arraycopy(b, partA, this.data, offset, partB);
            } else {
                System.arraycopy(b, off, this.data, baseOffset + offset, len);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = (markedReaderIndex + offset) % this.data.length;
            return this.data[baseOffset];
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = markedReaderIndex % this.data.length;
            if ((baseOffset + offset + len) > this.data.length) {
                int partA = this.data.length - (baseOffset + offset);
                int partB = len - partA;
                System.arraycopy(this.data, baseOffset, b, off, partA);
                System.arraycopy(this.data, offset, b, partA, partB);
                return partA + partB;
            } else {
                System.arraycopy(this.data, baseOffset + offset, b, off, len);
                return len;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected void extendByteBuf(int targetCapacity) {
        checkFree();

        if (this.getMaxCapacity() > 0 && targetCapacity > this.getMaxCapacity()) {
            throw new BufferOverflowException();
        }

        if (targetCapacity > this.data.length) {
            try {
                lock.writeLock().lock();
                byte[] newArray = new byte[targetCapacity];
                System.arraycopy(this.data, 0, newArray, 0, this.data.length);
                this.data = newArray;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    @Override
    public int capacity() {
        checkFree();
        return this.data.length;
    }

    @Override
    public ByteBuf copy() {
        checkFree();

        try {
            lock.writeLock().lock();

            ArrayByteBuf copy = new ArrayByteBuf(this.capacity(), this.getMaxCapacity());
            copy.data = this.data.clone();

            return copy;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void free() {
        if (this.isFree) {
            return;
        }

        try {
            lock.writeLock().lock();
            this.data = null;
            this.isFree = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
