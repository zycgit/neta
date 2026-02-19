/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

/**
 * Used to manipulate allocated memory blocks
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferTarget implements Buffer {
    static final int                          RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleHandler<BufferTarget> RECYCLE_HANDLER = new RecycleHandler<BufferTarget>() {
        public BufferTarget create() {
            return new BufferTarget();
        }

        @Override
        public void free(BufferTarget tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };
    private      Buffer                       memory;
    private      PageChunkSplit               pages;
    private      int                          pageSize;

    // ------------------------------------------------------------------------
    private boolean readOnly;
    private int     offset;
    private int     limit;
    private int     capacity;

    private BufferTarget() {
    }

    void initBuffer(int pageSize, PageChunkSplit pages, Buffer memory) {
        this.memory = memory;
        this.pages = pages;
        this.pageSize = pageSize;
        this.readOnly = false;

        this.offset = pageSize * pages.getFromPage();
        this.limit = pageSize * (pages.getToPage() + 1) - 1;
        this.capacity = this.limit - this.offset + 1;
    }

    void initBuffer(int pageSize, PageChunkSplit pages, Buffer memory, int offset, int limit, int capacity) {
        this.memory = memory;
        this.pages = pages;
        this.pageSize = pageSize;
        this.readOnly = false;
        this.offset = offset;
        this.limit = limit;
        this.capacity = capacity;
    }

    @Override
    public int capacity() {
        return this.capacity;
    }

    @Override
    public ByteBuffer getTarget() {
        if (!this.pages.isAvailable()) {
            throw new IllegalStateException("buffer is not Available.");
        }
        return this.memory.getTarget();
    }

    @Override
    public int getOffset() {
        return this.offset;
    }

    @Override
    public void free() {
        this.pages.free();
        this.memory = null;
        RECYCLE_HANDLER.free(this);
    }

    @Override
    public boolean isDirect() {
        return this.memory.isDirect();
    }

    @Override
    public boolean isAvailable() {
        return this.pages.isAvailable();
    }

    /** The pageSize of this buffer. */
    public int getPageSize() {
        return this.pages.getPageSize();
    }

    /** Queries if this buffer is read-only or not. */
    public boolean readOnly() {
        return this.readOnly;
    }

    /** Makes this buffer read-only. */
    public BufferTarget makeReadOnly() {
        if (!this.pages.isAvailable()) {
            throw new IllegalStateException("buffer is not Available.");
        }
        this.readOnly = true;
        return this;
    }

    /**
     * Splits the buffer into two, at the given splitOffset.
     * <pre>
     * Effectively, the following transformation takes place:
     *          This buffer:
     *           +--------------------------------+
     *          0|               |splitOffset     |cap
     *           +---------------+----------------+
     *          /               / \               \
     *         /               /   \               \
     *        /               /     \               \
     *       /               /       \               \
     *      /               /         \               \
     *     +---------------+           +---------------+
     *     |               |cap        |               |cap
     *     +---------------+           +---------------+
     *     Returned buffer.            This buffer.
     * </pre>
     */
    public BufferTarget split(int splitOffset) {
        if (this.capacity <= 1) {
            throw new IllegalStateException("Buffer only 1 byte and cannot be split.");
        }
        if (splitOffset >= this.capacity) {
            throw new IndexOutOfBoundsException("splitOffset out of range.");
        }

        checkOffset(splitOffset, 1, true);

        int newOffset = this.offset + splitOffset;
        int newCapacity = splitOffset + 1;

        // try physical split optimization
        PageChunkSplit headPages = null;
        int absoluteSplitEnd = newOffset + 1;
        if (absoluteSplitEnd % this.pageSize == 0) {
            int pagesForHead = (absoluteSplitEnd / this.pageSize) - this.pages.getFromPage();
            // Try to split logic
            headPages = this.pages.split(pagesForHead);
        }

        if (headPages == null) {
            // Fallback to shared view
            headPages = this.pages.duplicate();
        }

        // build new Buffer
        BufferTarget splitBuffer = RecycleObjectPool.get(BufferTarget.RECYCLE_INDEX, BufferTarget.RECYCLE_HANDLER);
        splitBuffer.initBuffer(this.pageSize, headPages, this.memory, this.offset, newOffset, newCapacity);

        // update self
        this.offset = newOffset + 1;
        this.capacity = this.capacity - newCapacity;

        return splitBuffer;
    }

    private void checkOffset(int index, int len, boolean isWrite) {
        if (!this.pages.isAvailable()) {
            throw new IllegalStateException("buffer is not Available.");
        }
        if (isWrite && this.readOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (index >= this.capacity) {
            throw new IndexOutOfBoundsException("Buffer index out of range, expect 0 ~ " + (this.capacity - 1) + ", encounter " + index);
        }
        if ((index + len) > this.capacity) {
            int allowLen = this.capacity - index;
            throw new IndexOutOfBoundsException("Buffer length out of range, expect 0 ~ " + allowLen + ", encounter " + len);
        }
    }

    @Override
    public byte get(int index) {
        checkOffset(index, 1, false);
        return this.memory.get(this.offset + index);
    }

    @Override
    public void put(int index, byte b) {
        checkOffset(index, 1, true);
        this.memory.put(this.offset + index, b);
    }

    @Override
    public void get(int index, byte[] dst, int dstOffset, int dstLen) {
        checkOffset(index, dstLen, false);
        this.memory.get(this.offset + index, dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, byte[] src, int dstOffset, int srcLen) {
        checkOffset(index, srcLen, true);
        this.memory.put(this.offset + index, src, dstOffset, srcLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstLen) {
        checkOffset(index, dstLen, false);
        this.memory.get(this.offset + index, dst, dstLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstOffset, int dstLen) {
        checkOffset(index, dstLen, false);
        this.memory.get(this.offset + index, dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcLen) {
        checkOffset(index, srcLen, true);
        this.memory.put(this.offset + index, src, srcLen);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcOffset, int srcLen) {
        checkOffset(index, srcLen, true);
        this.memory.put(this.offset + index, src, srcOffset, srcLen);
    }
}