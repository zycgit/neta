/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
/**
 * Per-segment I/O abstraction for {@link SwapFileByteBuf}.
 * <p>Two built-in implementations are provided:
 * <ul>
 *   <li>{@link SwapSegmentByHeap} – backed by {@link java.nio.channels.FileChannel}.
 *       Reads and writes pass through heap {@code byte[]} / {@code ByteBuffer}.
 *       Compatible with <em>all</em> JVM platforms, including Android.</li>
 *   <li>{@link SwapSegmentByDirect} – backed by {@link java.nio.MappedByteBuffer} (mmap).
 *       Each segment is pre-mapped into <em>native (off-heap)</em> memory at creation time.
 *       Data never touches the Java heap, reducing GC pressure and enabling near-zero-copy I/O.
 *       Requires OS/JVM {@code mmap} support — may be unavailable on some Android versions
 *       or sandboxed environments.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
interface SwapSegment extends Closeable {

    /**
     * Writes {@code len} bytes from {@code src[srcOff .. srcOff+len-1]} into the segment
     * at physical position {@code physPos}.
     */
    void writeFully(long physPos, byte[] src, int srcOff, int len) throws IOException;

    /**
     * Writes exactly {@code len} bytes from {@code src} (starting at {@code src.position()})
     * into the segment at physical position {@code physPos}.
     * Advances {@code src.position()} by {@code len}.
     */
    void writeFully(long physPos, ByteBuffer src, int len) throws IOException;

    /**
     * Reads up to {@code len} bytes from the segment at physical position {@code physPos}
     * into {@code dst[dstOff .. dstOff+len-1]}.
     * @return number of bytes actually read (may be less than {@code len} at end-of-written-data)
     */
    int readFully(long physPos, byte[] dst, int dstOff, int len) throws IOException;

    /**
     * Reads up to {@code len} bytes from the segment at physical position {@code physPos}
     * into {@code dst} (starting at {@code dst.position()}).
     * Advances {@code dst.position()} by the number of bytes transferred.
     * @return number of bytes actually read
     */
    int readFully(long physPos, ByteBuffer dst, int len) throws IOException;
}
