/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
/**
 * Basic read and write view over a memory block.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public interface Buffer {
    /** Returns whether the buffer is still available for access. */
    boolean isAvailable();

    /** Returns the total capacity in bytes. */
    int capacity();

    /** Returns the backing JVM buffer view. */
    ByteBuffer getTarget();

    /** Returns the absolute start offset in the backing storage. */
    int getOffset();

    /** Reads one byte at {@code index}. */
    byte get(int index);

    /** Writes one byte at {@code index}. */
    void put(int index, byte b);

    /** Copies bytes into the target array slice. */
    void get(int index, byte[] dst, int dstOffset, int dstLen);

    /** Copies bytes from the source array slice. */
    void put(int index, byte[] src, int srcOffset, int srcLen);

    /** Copies bytes into the target buffer. */
    void get(int index, ByteBuffer dst, int dstLen);

    /** Copies bytes into the target buffer range. */
    void get(int index, ByteBuffer dst, int dstOffset, int dstLen);

    /** Copies bytes from the source buffer. */
    void put(int index, ByteBuffer src, int srcLen);

    /** Copies bytes from the source buffer range. */
    void put(int index, ByteBuffer src, int srcOffset, int srcLen);

    /** Releases the underlying memory view. */
    void free();

    /**
     * Tells whether or not this byte buffer is direct.
     * @return <tt>true</tt> if, and only if, this buffer is direct
     */
    boolean isDirect();

    /**
     * Returns the backing heap byte array directly, or {@code null} if not available.
     * This allows avoiding {@link #getTarget()} which may trigger lazy ByteBuffer creation.
     */
    default byte[] heapArray() {
        return null;
    }

    /**
     * Returns the offset into the backing heap byte array, or 0 if not applicable.
     */
    default int heapArrayOffset() {
        return 0;
    }
}
