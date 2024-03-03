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
import net.hasor.cobble.ObjectUtils;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A block of memory managed by pooling, using the buddy algorithm.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-15
 */
class PageChunkPool {
    private final   int             memAddress;
    private final   int             pageSize;
    private final   int             pageCount;
    private final   int             capacity;
    private final   int             height;
    private final   PageChunk[]     chunksHeads;
    protected final byte[]          chunksMap;
    private final   ReentrantLock[] chunksLock;
    //
    private         Object          owner;
    private         double          usage;
    private         int             used;
    private         int             free;

    public PageChunkPool(int memAddress, int pageSize, int treeHeight) {
        this.memAddress = memAddress;
        this.pageSize = pageSize;
        this.pageCount = (int) Math.pow(2, treeHeight);
        this.height = treeHeight;
        this.chunksHeads = initPageChunks(ObjectUtils.checkPositive(treeHeight, "tree Height"));
        this.capacity = pageSize * (this.chunksHeads[0].getToPage() + 1);
        this.chunksMap = new byte[Math.max(1, this.pageCount / 8)];
        this.chunksLock = new ReentrantLock[this.chunksMap.length];
        for (int i = 0; i < this.chunksLock.length; i++) {
            this.chunksLock[i] = new ReentrantLock();
        }
    }

    public void setOwner(Object owner) {
        this.owner = owner;
    }

    public Object getOwner() {
        return this.owner;
    }

    private void updateUsage(int addon) {
        this.used = this.used + addon;
        this.free = this.pageCount - this.used;
        this.usage = ((double) this.used / (double) this.pageCount) * 100;
    }

    public double getUsage() {
        return this.usage;
    }

    /** The memory address used to mark memory blocks */
    public int getMemAddress() {
        return this.memAddress;
    }

    /** The capacity of this buffer, that is, the maximum number of bytes it can contain. */
    public int getCapacity() {
        return this.capacity;
    }

    /** Allocator page size */
    public int getPageSize() {
        return this.pageSize;
    }

    /** The total number of allocator pages */
    public int getPageCount() {
        return this.pageCount;
    }

    private PageChunk[] initPageChunks(int treeHeight) {
        int pageCount = (int) Math.pow(2, treeHeight);
        PageChunk[] chunksHeads = new PageChunk[treeHeight + 1];

        PageChunk parent = null;
        for (int i = 0; i <= treeHeight; i++) {
            int pageFrom = 0;
            int pageTo = pageCount / (int) Math.pow(2, i);
            int chunkList = pageCount / pageTo;

            // first chunks
            chunksHeads[i] = new PageChunk(this, pageFrom, pageTo - 1, null, parent);

            // chunk links
            PageChunk chunk = chunksHeads[i];
            for (int j = 1; j < chunkList; j++) {
                int from = chunk.getToPage() + 1;
                int to = from + pageTo - 1;
                chunk = new PageChunk(this, from, to, chunk, parent);
            }

            parent = chunksHeads[i];
        }

        return chunksHeads;
    }

    /** Returns a power of two size for the given target capacity. */
    private static int tableSizeFor(int cap, int maximumSize) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= maximumSize) ? maximumSize : n + 1;
    }

    private static final double LOG2 = Math.log(2);

    private static int log2(double antilogarithm) {
        return (int) (Math.log(antilogarithm) / LOG2);
    }

    /** Requests memory allocation and returns null if allocation fails. */
    public PageChunkSplit requestPages(int capacity) {
        if (capacity > this.capacity) {
            throw new OutOfMemoryPoolException("OutOfMemoryPool " + capacity + " (expected: 0-" + this.capacity + ")");
        }
        // n =  ?, 2 ^ (n-1) < reqCap < 2 ^ n <<< fmtSize
        // depth = height - log2(n) / pageSize
        int fmtSize = tableSizeFor(capacity, this.capacity) / pageSize;
        int usingHeight = this.height - log2(fmtSize);

        return this.allocFree(this.chunksHeads[usingHeight]);
    }

    private PageChunkSplit allocFree(PageChunk head) {
        PageChunk look = head;
        while (look != null) {
            if (isFree(look)) {
                if (tryLock(look, true)) {
                    try {
                        this.used(look);
                        return new PageChunkSplit(this, look.getFromPage(), look.getToPage());
                    } finally {
                        this.unLock(look);
                    }
                }
            }
            look = look.next;
        }
        return null;
    }

    protected static byte checkMask(int form, int to) {
        byte m1 = (byte) (0b11111111 >>> to + 1);
        if (form > 0) {
            byte m2 = (byte) ((byte) 0b10000000 >>> (form - 1));
            return (byte) (m1 | m2);
        } else {
            return m1;
        }
    }

    protected static byte useMask(int form, int to) {
        byte m1 = (byte) ((byte) 0b10000000 >> to);
        if (form > 0) {
            byte m2 = (byte) (0b11111111 >>> form);
            return (byte) (m1 & m2);
        } else {
            return m1;
        }
    }

    private boolean isFree(PageRange look) {
        int formByte = look.getFromPage() / 8;
        int formMask = look.getFromPage() % 8;
        int toByte = look.getToPage() / 8;
        int toMask = look.getToPage() % 8;

        if (formByte == toByte) {
            byte data = this.chunksMap[toByte];
            byte mask = checkMask(formMask, toMask);
            return data == (data & mask);
        } else {
            byte data1 = this.chunksMap[formByte];
            byte data2 = this.chunksMap[toByte];

            byte mask1 = (byte) (0b10000000 >> formMask);
            byte mask2 = (byte) (0b11111111 >>> toMask);

            for (int i = (formByte + 1); i < toByte; i++) {
                if (this.chunksMap[i] != 0) {
                    return false;
                }
            }
            return (data1 == (data1 & mask1)) && (data2 == (data2 & mask2));
        }
    }

    private void used(PageRange look) {
        int formByte = look.getFromPage() / 8;
        int formMask = look.getFromPage() % 8;
        int toByte = look.getToPage() / 8;
        int toMask = look.getToPage() % 8;
        int pageCnt = look.getToPage() - look.getFromPage() + 1;

        if (formByte == toByte) {
            byte mask = useMask(formMask, toMask);
            this.chunksMap[toByte] = (byte) (this.chunksMap[toByte] | mask);
        } else {
            byte mask1 = (byte) (0b11111111 >>> formMask);
            byte mask2 = useMask(0, toMask);

            for (int i = (formByte + 1); i < toByte; i++) {
                this.chunksMap[i] = -1;
            }
            this.chunksMap[formByte] = (byte) (this.chunksMap[formByte] | mask1);
            this.chunksMap[toByte] = (byte) (this.chunksMap[toByte] | mask2);
        }

        this.updateUsage(pageCnt);
    }

    void free(PageRange chunk) {
        int formByte = chunk.getFromPage() / 8;
        int formMask = chunk.getFromPage() % 8;
        int toByte = chunk.getToPage() / 8;
        int toMask = chunk.getToPage() % 8;
        int pageCnt = chunk.getToPage() - chunk.getFromPage() + 1;

        try {
            tryLock(chunk, false);

            if (formByte == toByte) {
                byte mask = useMask(formMask, toMask);
                this.chunksMap[toByte] = (byte) (this.chunksMap[toByte] & ~mask);
            } else {
                byte mask1 = (byte) (0b11111111 >>> formMask);
                byte mask2 = useMask(0, toMask);

                for (int i = (formByte + 1); i < toByte; i++) {
                    this.chunksMap[i] = 0;
                }
                this.chunksMap[formByte] = (byte) (this.chunksMap[formByte] & ~mask1);
                this.chunksMap[toByte] = (byte) (this.chunksMap[toByte] & ~mask2);
            }

            this.updateUsage(-pageCnt);
        } finally {
            unLock(chunk);
        }
    }

    private boolean tryLock(PageRange look, boolean tryLock) {
        int formByte = look.getFromPage() / 8;
        int toByte = look.getToPage() / 8;

        boolean isFailed = false;
        int getLock = -1;
        for (int i = formByte; i <= toByte; i++) {
            boolean locked;
            if (tryLock) {
                locked = this.chunksLock[i].tryLock();
            } else {
                this.chunksLock[i].lock();
                locked = true;
            }

            if (locked) {
                getLock = i;
            } else {
                isFailed = true;
                break;
            }
        }

        if (isFailed) {
            for (int i = formByte; i <= getLock; i++) {
                this.chunksLock[i].unlock();
            }
            return false;
        } else {
            return true;
        }
    }

    private void unLock(PageRange look) {
        int formByte = look.getFromPage() / 8;
        int toByte = look.getToPage() / 8;
        for (int i = formByte; i <= toByte; i++) {
            this.chunksLock[i].unlock();
        }
    }

    @Override
    public String toString() {
        return "Chunks(" + Integer.toHexString(System.identityHashCode(this)) +//
                ", usage: " + this.usage + "%" +//
                ", free:" + this.free +         //
                ", used:" + this.used + "/" + this.pageCount + //
                ", pageSize:" + this.pageSize + ")";
    }
    //public static String binary(byte bytes) { return HexUtils.bytes2bit(new byte[] { bytes }); }
}