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
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

/**
 * {@link SwapSegment} implementation backed by a {@link MappedByteBuffer} (memory-mapped file).
 * <p>The entire {@code segmentSize} region is pre-mapped into <em>native (off-heap)</em> memory
 * at construction time via {@link FileChannel#map FileChannel.map(READ_WRITE, 0, segmentSize)}.
 * Reads and writes operate directly on the mapped region — data never touches the Java heap,
 * which:
 * <ul>
 *   <li>Eliminates the kernel↔heap copy present in {@link SwapSegmentByHeap}.</li>
 *   <li>Reduces GC pause time for large buffers (mapped memory is invisible to GC).</li>
 *   <li>Lets the OS page-cache reclaim cold pages automatically under memory pressure.</li>
 * </ul>
 * <h3>Trade-offs vs. heap mode</h3>
 * <pre>
 *  HeapSwapSegmentIO (FileChannel)        DirectSwapSegmentIO (MappedByteBuffer)
 *  ─────────────────────────────────────  ──────────────────────────────────────
 *  kernel → copy → Java heap byte[]       kernel maps page directly to process VA
 *  File grows incrementally as written    File pre-extended to segmentSize upfront
 *  Works everywhere (incl. Android)       Requires OS/JVM mmap (not on all Android)
 *  GC must track every byte[]             Mapped region invisible to GC
 * </pre>
 * <p><b>Platform note:</b> Some Android versions (especially in strict sandboxes) and certain
 * embedded JVMs may throw {@link UnsupportedOperationException} or {@link IOException} on
 * {@link FileChannel#map}.  Fall back to {@link SwapSegmentByHeap} in those environments.
 * @author 赵永春 (zyc@hasor.net)
 */
final class SwapSegmentByDirect implements SwapSegment {
    private final RandomAccessFile raf;
    private final FileChannel      channel;
    private final MappedByteBuffer mmap;

    SwapSegmentByDirect(File file, int segmentSize) throws IOException {
        this.raf = new RandomAccessFile(file, "rw");
        this.channel = this.raf.getChannel();
        // Pre-extend the file so that the mmap can cover the full segment region.
        // Without this, channel.map() will throw IOException on most OS.
        this.raf.setLength(segmentSize);
        this.mmap = this.channel.map(MapMode.READ_WRITE, 0, segmentSize);
    }

    @Override
    public void writeFully(long physPos, byte[] src, int srcOff, int len) {
        // MappedByteBuffer.position() + put() — Java 8 compatible absolute write.
        this.mmap.position((int) physPos);
        this.mmap.put(src, srcOff, len);
    }

    @Override
    public void writeFully(long physPos, ByteBuffer src, int len) {
        // Temporarily narrow src to exactly len bytes, then bulk-transfer to mmap.
        int origLim = src.limit();
        src.limit(src.position() + len);
        this.mmap.position((int) physPos);
        this.mmap.put(src);   // advances src.position() by len
        src.limit(origLim);
    }

    @Override
    public int readFully(long physPos, byte[] dst, int dstOff, int len) {
        this.mmap.position((int) physPos);
        this.mmap.get(dst, dstOff, len);
        return len;
    }

    @Override
    public int readFully(long physPos, ByteBuffer dst, int len) {
        // Slice mmap to [physPos, physPos+len) so dst.put(view) transfers exactly len bytes.
        this.mmap.position((int) physPos);
        this.mmap.limit((int) physPos + len);
        int origLim = dst.limit();
        dst.limit(dst.position() + len);
        dst.put(this.mmap);                  // both positions advance by len
        this.mmap.limit(this.mmap.capacity()); // restore mmap limit
        dst.limit(origLim);
        return len;
    }

    @Override
    public void close() throws IOException {
        // MappedByteBuffer has no close() method; the native mapping is released when
        // the MappedByteBuffer is GC'd (or via sun.misc.Cleaner on supported JVMs).
        // Closing the channel and raf marks the file as closeable by the OS.
        try {
            this.channel.close();
        } finally {
            this.raf.close();
        }
    }
}
