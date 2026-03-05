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
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * A memory-backed {@link ByteBuf} that automatically spills data to temporary files once
 * the in-memory write position exceeds {@code memThreshold}.
 * <h3>Two-Phase Storage</h3>
 * <pre>
 *  Memory mode  (writerIndex &le; memThreshold)
 *  ─────────────────────────────────────────────────────────────────
 *  ┌───────────────────────────────────────────────────────────────┐
 *  │                       heap byte[]                             │
 *  │  [  consumed  │      readable      │    writable space    ]   │
 *  │               ↑                    ↑                          │
 *  │          readerIndex           writerIndex                    │
 *  └───────────────────────────────────────────────────────────────┘
 *              │
 *              │  when writerIndex &gt; memThreshold
 *              ▼
 *  File mode  (spill to a deque of fixed-size temporary file segments)
 *  ─────────────────────────────────────────────────────────────────
 *  absoluteBase (monotonically increasing; bytes consumed so far)
 *       │
 *       ▼
 *  ╔═══════════╦═══════════╦════════════╗
 *  ║ Segment 0 ║ Segment 1 ║ Segment 2  ║   ArrayDeque (head → tail)
 *  ║ prefix0   ║ prefix1   ║ prefix2    ║
 *  ║  .buf     ║  .buf     ║  .buf      ║
 *  ║  FULL     ║  FULL     ║  partial   ║
 *  ╚═══════════╩═══════════╩════════════╝
 *       ↑                        ↑
 *  head: deleted when          tail: new data appended here
 *  absoluteBase &ge; logicalStart + segmentSize
 * </pre>
 * <h3>Segment Deque — Zero-Copy Head Removal</h3>
 * <pre>
 *  Before consuming Segment 0  (absoluteBase = 0):
 *  [Seg0: 0..512KB] [Seg1: 512KB..1MB] [Seg2: 1MB..partial]
 *  After markReader() advances absoluteBase to 512KB:
 *  → Seg0 file deleted (O(1), no data copy)
 *  [Seg1: 512KB..1MB] [Seg2: 1MB..partial]
 * </pre>
 * <h3>Absolute Offset Mapping</h3>
 * <pre>
 *  physical file position = (absoluteBase + logicalIndex) - segment.logicalStart
 *  logicalIndex        : byte offset within the current logical window [0 .. writerIndex)
 *  segment.logicalStart: the absolute stream position where this segment begins
 * </pre>
 * <h3>File I/O Strategies</h3>
 * <pre>
 *  Heap mode (default)              Direct mode (isDirect = true)
 *  ───────────────────────────────  ─────────────────────────────────────
 *  FileChannel + heap byte[]        MappedByteBuffer (mmap, off-heap)
 *  kernel → copy → Java heap        kernel maps page into process VA space
 *  File grows as data written        File pre-extended to segmentSize upfront
 *  Works on all JVMs / Android       Requires OS mmap (not on all Android)
 *  GC tracks every byte[]            Mapped memory is invisible to GC
 * </pre>
 * <p>Allocate via {@link ByteBufAllocator#swapFile()} (heap) or
 * {@link ByteBufAllocator#swapFileDirect()} (direct).
 * <h3>Capacity Constraints</h3>
 * <pre>
 *  Total stream bytes  : unlimited — {@code absoluteBase} is {@code long} (64-bit).
 *  Logical window size : unlimited — {@code writerIndex} and all index
 *                        fields in {@link AbstractByteBuf} are {@code long}.
 *  In streaming use (write → markWriter → read → markReader → repeat) the
 *  window stays small because markReader() subtracts the consumed offset from
 *  writerIndex, keeping it near zero.
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 */
public class SwapFileByteBuf extends AbstractByteBuf {
    public static final  int                 DEFAULT_MEM_THRESHOLD    = 512 * 1024;         // memory threshold 512 KB
    public static final  int                 DEFAULT_SEGMENT_SIZE     = 64 * 1024 * 1024;   // temporary file segment: 64MB
    public static final  String              DEFAULT_TEMP_FILE_PREFIX = "neta-swap-";       // temporary swap files.
    private static final int                 MEM_INIT_SIZE            = 4 * 1024;
    //
    private final        int                 memThreshold;
    private final        int                 segmentSize;
    private final        String              tempFilePrefix;
    private final        boolean             direct;
    private final        ArrayDeque<Segment> segments                 = new ArrayDeque<>();
    private              byte[]              memBuf;
    private              boolean             fileMode                 = false;
    /**
     * Monotonically increasing count of bytes consumed so far.
     * In file mode: {@code absoluteBase + logicalIndex} gives the absolute stream position.
     * Advanced by markReader / discardReadBytes / sliceOff; never reset to zero.
     */
    private              long                absoluteBase             = 0;

    /** Heap mode (FileChannel). Equivalent to {@code SwapFileByteBuf(alloc, memThreshold, segmentSize, tempFilePrefix, false)}. */
    SwapFileByteBuf(ByteBufAllocator alloc, int memThreshold, int segmentSize, String tempFilePrefix) {
        this(alloc, memThreshold, segmentSize, tempFilePrefix, false);
    }

    /**
     * Full constructor.
     * @param direct {@code false} = heap mode ({@link SwapSegmentByHeap}),
     * {@code true}  = direct mode ({@link SwapSegmentByDirect} / mmap)
     */
    SwapFileByteBuf(ByteBufAllocator alloc, int memThreshold, int segmentSize, String tempFilePrefix, boolean direct) {
        this.memThreshold = memThreshold;
        this.segmentSize = segmentSize;
        this.tempFilePrefix = (tempFilePrefix != null && !tempFilePrefix.isEmpty()) ? tempFilePrefix : DEFAULT_TEMP_FILE_PREFIX;
        this.direct = direct;
        int initSize = memThreshold > 0 ? Math.min(MEM_INIT_SIZE, memThreshold) : MEM_INIT_SIZE;
        this.memBuf = new byte[initSize];
        super.initByteBuf(alloc, Integer.MAX_VALUE);
    }

    // ── mode switch ───────────────────────────────────────────────────────────

    /**
     * Flushes committed in-memory data into the first Segment and switches to file mode.
     * {@code absoluteBase} is 0 at this point because memory mode keeps data starting at index 0
     * (enforced via {@link #memCompact()}).
     */
    private void switchToFile(int committedEnd) {
        try {
            Segment seg = createSegment(0L);
            this.segments.add(seg);
            if (committedEnd > 0) {
                writeSpanning(0L, this.memBuf, 0, committedEnd);
            }
            this.memBuf = null;
            this.fileMode = true;
            // absoluteBase 保持 0
        } catch (IOException e) {
            throw new RuntimeException("SwapFileByteBuf: failed to switch to file mode", e);
        }
    }

    // ── Segment management ────────────────────────────────────────────────────

    private Segment createSegment(long logicalStart) throws IOException {
        File f = File.createTempFile(this.tempFilePrefix, ".buf");
        f.deleteOnExit();
        SwapSegment io = this.direct ? new SwapSegmentByDirect(f, this.segmentSize) : new SwapSegmentByHeap(f);
        return new Segment(logicalStart, f, io);
    }

    /**
     * Returns the Segment responsible for writing at absolute position {@code absPos},
     * creating a new tail Segment if the current one is full.
     * Writes always append to the tail, so only the last segment needs to be checked.
     */
    private Segment ensureWriteSegment(long absPos) {
        if (this.segments.isEmpty()) {
            try {
                long start = (absPos / this.segmentSize) * this.segmentSize;
                this.segments.add(createSegment(start));
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf: cannot create segment", e);
            }
        }
        while (true) {
            Segment last = this.segments.peekLast();
            if (absPos < last.logicalStart + this.segmentSize) {
                return last;
            }
            try {
                this.segments.add(createSegment(last.logicalStart + this.segmentSize));
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf: cannot create segment", e);
            }
        }
    }

    /**
     * Finds the Segment containing absolute position {@code absPos} for reading.
     * Falls back to the tail Segment for a partially-filled last segment whose actual
     * written size is less than {@code segmentSize}.
     */
    private Segment findReadSegment(long absPos) {
        for (Segment s : this.segments) {
            if (absPos >= s.logicalStart && absPos < s.logicalStart + this.segmentSize) {
                return s;
            }
        }
        // The tail Segment may be partially filled: its actual written size < segmentSize,
        // so the range check above may miss it. Fall back to peekLast as a safety net.
        Segment last = this.segments.peekLast();
        if (last != null && absPos >= last.logicalStart) {
            return last;
        }
        throw new IllegalStateException("No segment found for absPos=" + absPos + ", absoluteBase=" + this.absoluteBase);
    }

    /**
     * Deletes fully consumed head Segments (O(1) amortized — each Segment is created and deleted exactly once).
     * A Segment is considered fully consumed when {@code absoluteBase >= segment.logicalStart + segmentSize}.
     */
    private void releaseConsumedSegments() {
        while (!this.segments.isEmpty()) {
            Segment first = this.segments.peek();
            if (this.absoluteBase >= first.logicalStart + this.segmentSize) {
                this.segments.poll().delete();
            } else {
                break;
            }
        }
    }

    // ── Spanning I/O ──────────────────────────────────────────────────────────

    /** Writes {@code src[srcOff .. srcOff+len-1]} into Segments starting at absolute position {@code absStart}, spanning segment boundaries as needed. */
    private void writeSpanning(long absStart, byte[] src, int srcOff, int len) throws IOException {
        long pos = absStart;
        int done = 0;
        while (done < len) {
            Segment seg = ensureWriteSegment(pos);
            long physPos = pos - seg.logicalStart;
            int canWrite = (int) Math.min(len - done, (long) this.segmentSize - physPos);
            seg.io.writeFully(physPos, src, srcOff + done, canWrite);
            pos += canWrite;
            done += canWrite;
        }
    }

    /** Writes {@code len} bytes from {@code src} into Segments starting at absolute position {@code absStart}, spanning segment boundaries as needed. */
    private void writeSpanning(long absStart, ByteBuffer src, int len) throws IOException {
        long pos = absStart;
        int done = 0;
        while (done < len) {
            Segment seg = ensureWriteSegment(pos);
            long physPos = pos - seg.logicalStart;
            int canWrite = (int) Math.min(len - done, (long) this.segmentSize - physPos);
            seg.io.writeFully(physPos, src, canWrite);
            pos += canWrite;
            done += canWrite;
        }
    }

    /** Reads {@code len} bytes from absolute position {@code absStart} into {@code dst[dstOff..]}, spanning segment boundaries as needed. */
    private int readSpanning(long absStart, byte[] dst, int dstOff, int len) throws IOException {
        long pos = absStart;
        int done = 0;
        while (done < len) {
            Segment seg = findReadSegment(pos);
            long physPos = pos - seg.logicalStart;
            int canRead = (int) Math.min(len - done, (long) this.segmentSize - physPos);
            int got = seg.io.readFully(physPos, dst, dstOff + done, canRead);
            pos += got;
            done += got;
            if (got < canRead) {
                break;
            }
        }
        return done;
    }

    /** Reads {@code len} bytes from absolute position {@code absStart} into {@code dst}, spanning segment boundaries as needed. */
    private int readSpanning(long absStart, ByteBuffer dst, int len) throws IOException {
        long pos = absStart;
        int done = 0;
        while (done < len) {
            Segment seg = findReadSegment(pos);
            long physPos = pos - seg.logicalStart;
            int canRead = (int) Math.min(len - done, (long) this.segmentSize - physPos);
            int segDone = seg.io.readFully(physPos, dst, canRead);
            pos += segDone;
            done += segDone;
            if (segDone < canRead) {
                break;
            }
        }
        return done;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();
        if (!this.fileMode && this.writerIndex > this.memThreshold) {
            switchToFile((int) offset);
        }
        if (this.fileMode) {
            try {
                byte[] tmp = { b };
                writeSpanning(this.absoluteBase + offset, tmp, 0, 1);
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf write error", e);
            }
        } else {
            ensureMemCapacity((int) (offset + 1));
            this.memBuf[(int) offset] = b;
        }
    }

    // ── AbstractByteBuf abstract methods ──────────────────────────────────────

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();
        if (!this.fileMode && this.writerIndex > this.memThreshold) {
            switchToFile((int) offset);
        }
        if (this.fileMode) {
            try {
                writeSpanning(this.absoluteBase + offset, src, srcOffset, srcLen);
                return srcLen;
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf write error", e);
            }
        } else {
            ensureMemCapacity((int) (offset + srcLen));
            System.arraycopy(src, srcOffset, this.memBuf, (int) offset, srcLen);
            return srcLen;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();
        if (!this.fileMode && this.writerIndex > this.memThreshold) {
            switchToFile((int) offset);
        }
        if (this.fileMode) {
            try {
                writeSpanning(this.absoluteBase + offset, src, srcLen);
                return srcLen;
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf write error", e);
            }
        } else {
            ensureMemCapacity((int) (offset + srcLen));
            src.get(this.memBuf, (int) offset, srcLen);
            return srcLen;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        byte[] tmp = new byte[srcLen];
        src.readBytes(tmp, 0, srcLen);
        return _putBytes(offset, tmp, 0, srcLen);
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();
        if (this.fileMode) {
            try {
                byte[] tmp = new byte[1];
                readSpanning(this.absoluteBase + offset, tmp, 0, 1);
                return tmp[0];
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf read error", e);
            }
        } else {
            return this.memBuf[(int) offset];
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();
        if (this.fileMode) {
            try {
                return readSpanning(this.absoluteBase + offset, dst, dstOffset, dstLen);
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf read error", e);
            }
        } else {
            System.arraycopy(this.memBuf, (int) offset, dst, dstOffset, dstLen);
            return dstLen;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();
        if (this.fileMode) {
            try {
                return readSpanning(this.absoluteBase + offset, dst, dstLen);
            } catch (IOException e) {
                throw new RuntimeException("SwapFileByteBuf read error", e);
            }
        } else {
            dst.put(this.memBuf, (int) offset, dstLen);
            return dstLen;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        byte[] tmp = new byte[dstLen];
        int n = _getBytes(offset, tmp, 0, dstLen);
        dst.writeBytes(tmp, 0, n);
        return n;
    }

    @Override
    protected void _free() {
        for (Segment seg : this.segments) {
            seg.delete();
        }
        this.segments.clear();
        this.memBuf = null;
        this.fileMode = false;
    }

    /** Advances the reader mark. In file mode, advances {@code absoluteBase} and releases fully consumed head Segments (O(1), no data copy). */
    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex == this.readerIndex) {
            return this;
        }
        int shift = this.readerIndex;
        if (this.fileMode) {
            this.absoluteBase += shift;
            this.writerIndex -= shift;
            this.markedWriterIndex = Math.max(0, this.markedWriterIndex - shift);
            this.readerIndex = 0;
            this.markedReaderIndex = 0;
            releaseConsumedSegments();
        } else {
            this.markedReaderIndex = shift;
            memCompact();
        }
        return this;
    }

    // ── mark / discard ────────────────────────────────────────────────────────

    /** Discards all bytes before {@code readerIndex}. In file mode, advances {@code absoluteBase} and deletes consumed Segments with no data copy. */
    @Override
    public void discardReadBytes() {
        int shift = this.readerIndex;
        if (shift == 0) {
            return;
        }
        if (this.fileMode) {
            this.absoluteBase += shift;
            this.writerIndex -= shift;
            this.markedWriterIndex = Math.max(0, this.markedWriterIndex - shift);
            this.markedReaderIndex = Math.max(0, this.markedReaderIndex - shift);
            this.readerIndex = 0;
            releaseConsumedSegments();
        } else {
            int liveLen = (this.writerIndex - shift);
            if (liveLen > 0) {
                System.arraycopy(this.memBuf, shift, this.memBuf, 0, liveLen);
            }
            this.writerIndex -= shift;
            this.markedWriterIndex = Math.max(0, this.markedWriterIndex - shift);
            this.markedReaderIndex = Math.max(0, this.markedReaderIndex - shift);
            this.readerIndex = 0;
        }
    }

    @Override
    public ByteBuf sliceOff(int splitOffset) {
        if (splitOffset == 0) {
            return ByteBuf.EMPTY;
        }
        if (splitOffset < 0 || splitOffset > this.markedWriterIndex) {
            throw new IndexOutOfBoundsException("splitOffset=" + splitOffset + " markedWriterIndex=" + this.markedWriterIndex);
        }
        // 读取待切走的头部数据
        byte[] sliceData = new byte[splitOffset];
        _getBytes(0, sliceData, 0, splitOffset);

        // 文件模式：仅推进 absoluteBase，无任何文件数据搬移
        if (this.fileMode) {
            this.absoluteBase += splitOffset;
            releaseConsumedSegments();
        } else {
            // 内存模式：原地左移剩余数据
            int liveLen = (this.writerIndex - splitOffset);
            if (liveLen > 0) {
                System.arraycopy(this.memBuf, splitOffset, this.memBuf, 0, liveLen);
            }
        }
        this.writerIndex = Math.max(0, this.writerIndex - splitOffset);
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - splitOffset);
        this.readerIndex = Math.max(0, this.readerIndex - splitOffset);
        this.markedReaderIndex = Math.max(0, this.markedReaderIndex - splitOffset);

        java.nio.ByteBuffer nb = java.nio.ByteBuffer.wrap(sliceData);
        WrapByteBuffer sliceBuf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
        sliceBuf.initBuffer(nb, false);
        return sliceBuf;
    }

    // ── capacity / isDirect / copy ────────────────────────────────────────────

    @Override
    public int capacity() {
        if (this.fileMode) {
            return this.writerIndex;
        }
        return this.memBuf != null ? this.memBuf.length : 0;
    }

    @Override
    public boolean isDirect() {
        return this.direct;
    }

    @Override
    public ByteBuf copy() {
        checkFree();
        int start = this.markedReaderIndex;
        int end = this.markedWriterIndex;
        int len = (int) (end - start);
        byte[] data = new byte[Math.max(0, len)];
        if (len > 0) {
            _getBytes(start, data, 0, len);
        }
        SwapFileByteBuf copy = new SwapFileByteBuf(this.alloc, this.memThreshold, this.segmentSize, this.tempFilePrefix, this.direct);
        if (len > 0) {
            copy.writeBytes(data);
        }
        copy.markWriter();
        copy.markReader();
        copy.byteOrder = this.byteOrder;
        copy.bigEndian = this.bigEndian;
        return copy;
    }

    @Override
    protected String getSimpleName() {
        return "SwapFileByteBuf";
    }

    /** Returns {@code true} if this buffer has switched to file mode (spilled to disk). */
    public boolean isFileMode() {
        return this.fileMode;
    }

    // ── public state ──────────────────────────────────────────────────────────

    /**
     * Returns the temporary file of the current head Segment, or {@code null} in memory mode.
     * Primarily used in tests to verify that files are created and deleted after {@link #free()}.
     */
    public File getTempFile() {
        if (!this.fileMode || this.segments.isEmpty()) {
            return null;
        }
        return this.segments.peek().file;
    }

    /** Returns the total number of bytes consumed so far (monotonically increasing). Never reset to zero even when Segments are released. */
    public long getAbsoluteBase() {
        return this.absoluteBase;
    }

    /** Returns the number of currently active Segments (meaningful only in file mode). */
    public int getActiveSegmentCount() {
        return this.segments.size();
    }

    private void ensureMemCapacity(int required) {
        if (this.memBuf != null && this.memBuf.length >= required) {
            return;
        }
        int curLen = this.memBuf != null ? this.memBuf.length : 0;
        int newCap = Math.max(required, curLen + MEM_INIT_SIZE);
        byte[] nb = new byte[newCap];
        if (curLen > 0) {
            System.arraycopy(this.memBuf, 0, nb, 0, curLen);
        }
        this.memBuf = nb;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** In memory mode, shifts live data [markedReaderIndex .. writerIndex) to index 0 and resets all pointers. */
    private void memCompact() {
        long shift = this.markedReaderIndex;
        if (shift == 0) {
            return;
        }
        int liveLen = (int) (this.writerIndex - shift);
        if (liveLen > 0) {
            System.arraycopy(this.memBuf, (int) shift, this.memBuf, 0, liveLen);
        }
        this.writerIndex -= shift;
        this.markedWriterIndex -= shift;
        this.readerIndex -= shift;
        this.markedReaderIndex = 0;
    }

    /** A fixed-size temporary file segment in the spill deque. */
    private static final class Segment {
        /** The absolute stream position at which this segment begins. */
        final long        logicalStart;
        final File        file;
        final SwapSegment io;

        Segment(long logicalStart, File file, SwapSegment io) {
            this.logicalStart = logicalStart;
            this.file = file;
            this.io = io;
        }

        void delete() {
            try {
                this.io.close();
            } catch (IOException ignored) {
            }
            this.file.delete();
        }
    }
}
