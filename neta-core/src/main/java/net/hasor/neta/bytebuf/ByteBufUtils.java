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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndData;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Central registry for the {@link ByteBufAllocator}s pre-wired in Neta and
 * a set of utility methods used by the framework internally.
 * <p><b>Five pre-built allocators:</b>
 * <table border="1" summary="pre-built allocators">
 *   <tr><th>Constant</th><th>Pooled</th><th>Memory type</th></tr>
 *   <tr><td>{@link #DEFAULT_ALLOCATOR}</td><td>configurable</td><td>configurable</td></tr>
 *   <tr><td>{@link #POOLED_HEAP_ALLOCATOR}</td><td>yes</td><td>JVM heap</td></tr>
 *   <tr><td>{@link #POOLED_DIRECT_ALLOCATOR}</td><td>yes</td><td>off-heap direct</td></tr>
 *   <tr><td>{@link #UNPOOLED_HEAP_ALLOCATOR}</td><td>no</td><td>JVM heap</td></tr>
 *   <tr><td>{@link #UNPOOLED_DIRECT_ALLOCATOR}</td><td>no</td><td>off-heap direct</td></tr>
 * </table>
 * <p>{@link #DEFAULT_ALLOCATOR} is selected at class-load time via system properties:
 * <ul>
 *   <li>{@code neta.bytebuf.type} — {@code pooled} (default) or {@code unpooled}.</li>
 *   <li>{@code neta.bytebuf.mem} — {@code heap} (default) or {@code direct}.</li>
 *   <li>{@code neta.bytebuf.sliceSize} — growth-step size in bytes (default 1024).</li>
 * </ul>
 * <p>{@link #CLEANER} is wired at class-load time to either
 * {@code BufferCleanerJava9} or {@code BufferCleanerJava6} depending on the
 * detected JDK version.  It is {@code null} if {@code sun.misc.Unsafe} is
 * unavailable on the current security policy.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see ByteBufAllocator
 * @see BasicByteBufAllocator
 * @see BufferCleaner
 */
public class ByteBufUtils {
    public static final ByteBufAllocator DEFAULT_ALLOCATOR;
    public static final ByteBufAllocator POOLED_HEAP_ALLOCATOR;
    public static final ByteBufAllocator POOLED_DIRECT_ALLOCATOR;
    public static final ByteBufAllocator UNPOOLED_HEAP_ALLOCATOR;
    public static final ByteBufAllocator UNPOOLED_DIRECT_ALLOCATOR;
    public static final BufferCleaner    CLEANER;
    /** <p>The system default newline character.</p> */
    static final String                  NEWLINE = SystemUtils.getSystemProperty("line.separator", "\n");
    private static final Logger          logger  = Logger.getLogger(ByteBufUtils.class);

    // ensure DEFAULT
    static {
        if (SystemUtils.getJavaVersion() >= 9) {
            CLEANER = BufferCleanerJava9.isSupported() ? new BufferCleanerJava9() : null;
        } else {
            CLEANER = BufferCleanerJava6.isSupported() ? new BufferCleanerJava6() : null;
        }

        String allocType = SystemUtils.getSystemProperty("neta.bytebuf.type", isPooled() ? "pooled" : "unpooled");
        String memType = SystemUtils.getSystemProperty("neta.bytebuf.mem", isDirect() ? "direct" : "heap");
        String sliceSize = SystemUtils.getSystemProperty("neta.bytebuf.sliceSize", String.valueOf(4 * 1024));
        String initialSize = SystemUtils.getSystemProperty("neta.bytebuf.initialSize", String.valueOf(4 * 1024));

        int sliceSizeByDefault = Integer.parseInt(sliceSize);
        int initialCapacityByDefault = Integer.parseInt(initialSize);

        UNPOOLED_HEAP_ALLOCATOR = new BasicByteBufAllocator(false, initialCapacityByDefault, sliceSizeByDefault) {
            @Override
            public boolean isDirect() {
                return false;
            }

            @Override
            public ByteBuffer jvmBuffer(int capacity) {
                return ByteBuffer.allocate(capacity);
            }
        };
        POOLED_HEAP_ALLOCATOR = new BasicByteBufAllocator(true, initialCapacityByDefault, sliceSizeByDefault) {
            @Override
            public boolean isDirect() {
                return false;
            }

            @Override
            public ByteBuffer jvmBuffer(int capacity) {
                return ByteBuffer.allocate(capacity);
            }
        };
        UNPOOLED_DIRECT_ALLOCATOR = new BasicByteBufAllocator(false, initialCapacityByDefault, sliceSizeByDefault) {
            @Override
            public boolean isDirect() {
                return true;
            }

            @Override
            public ByteBuffer jvmBuffer(int capacity) {
                return ByteBuffer.allocateDirect(capacity);
            }
        };
        POOLED_DIRECT_ALLOCATOR = new BasicByteBufAllocator(true, initialCapacityByDefault, sliceSizeByDefault) {
            @Override
            public boolean isDirect() {
                return true;
            }

            @Override
            public ByteBuffer jvmBuffer(int capacity) {
                return ByteBuffer.allocateDirect(capacity);
            }
        };

        allocType = allocType.toLowerCase().trim();
        memType = memType.toLowerCase().trim();

        if ("unpooled".equals(allocType)) {
            if ("heap".equals(memType)) {
                logger.debug("-Dneta.bytebuf.type: unpooled -Dneta.bytebuf.mem: heap");
                DEFAULT_ALLOCATOR = UNPOOLED_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug("-Dneta.bytebuf.type: unpooled -Dneta.bytebuf.mem: direct");
                DEFAULT_ALLOCATOR = UNPOOLED_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dneta.bytebuf.type: unpooled -Dneta.bytebuf.mem: heap (unknown: %s)", memType));
                DEFAULT_ALLOCATOR = UNPOOLED_HEAP_ALLOCATOR;
            }
        } else if ("pooled".equals(allocType)) {
            if ("heap".equals(memType)) {
                logger.debug("-Dneta.bytebuf.type: pooled -Dneta.bytebuf.mem: heap");
                DEFAULT_ALLOCATOR = POOLED_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug("-Dneta.bytebuf.type: pooled -Dneta.bytebuf.mem: direct");
                DEFAULT_ALLOCATOR = POOLED_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dneta.bytebuf.type: pooled -Dneta.bytebuf.mem: heap (unknown: %s)", memType));
                DEFAULT_ALLOCATOR = POOLED_HEAP_ALLOCATOR;
            }
        } else {
            if ("heap".equals(memType)) {
                logger.debug(String.format("-Dneta.bytebuf.type: pooled (unknown: %s) -Dneta.bytebuf.mem: heap", allocType));
                DEFAULT_ALLOCATOR = UNPOOLED_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug(String.format("-Dneta.bytebuf.type: pooled (unknown: %s) -Dneta.bytebuf.mem: direct", allocType));
                DEFAULT_ALLOCATOR = UNPOOLED_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dneta.bytebuf.type: pooled (unknown: %s) -Dneta.bytebuf.mem: heap (unknown: %s)", allocType, memType));
                DEFAULT_ALLOCATOR = UNPOOLED_HEAP_ALLOCATOR;
            }
        }
    }

    /** Returns a non-null default allocator, even during early static initialization. */
    static ByteBufAllocator defaultAllocator() {
        ByteBufAllocator alloc = DEFAULT_ALLOCATOR;
        if (alloc != null) {
            return alloc;
        }
        alloc = UNPOOLED_HEAP_ALLOCATOR;
        if (alloc != null) {
            return alloc;
        }
        // emergency: static init has not assigned any allocators yet
        return new BasicByteBufAllocator(false, 4096, 4096) {
            @Override
            public boolean isDirect() {
                return false;
            }

            @Override
            public java.nio.ByteBuffer jvmBuffer(int capacity) {
                return java.nio.ByteBuffer.allocate(capacity);
            }
        };
    }

    private static boolean isPooled() {
        return !SystemUtils.isAndroid();
    }

    private static boolean isDirect() {
        if (SystemUtils.isAndroid()) {
            return false;
        }

        return CLEANER != null;
    }

    /** Copies the readable bytes of the buffer into a new byte array. */
    public static byte[] toBytes(ByteBuf buf) {
        int available = buf.readableBytes();
        byte[] bytes = new byte[available];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Create a read-only {@link ByteBuf} that presents all queued {@link ByteBuf}
     * messages in the given {@link net.hasor.neta.channel.data.ProtoRcvQueue ProtoRcvQueue}
     * as a single contiguous readable buffer.
     * <p>
     * The returned buffer is a zero-copy composite view over the queue's messages.
     * Call {@link ByteBuf#markReader()} to consume fully-read messages from the queue.
     * Write operations are not supported.
     * @param queue the receive queue to wrap (must not be null)
     * @return a new read-only ByteBuf wrapping the queue
     * @throws NullPointerException if queue is null
     */
    public static ByteBuf queueBuffer(ProtoRcvQueue<ByteBuf> queue) {
        return new QueueByteBuf(queue);
    }

    /**
     * Create a new empty {@link CompositeByteBuf} using the default allocator.
     * <p>
     * Components can be dynamically appended via {@link CompositeByteBuf#addComponent(ByteBuf)}.
     * @return a new empty CompositeByteBuf
     */
    public static CompositeByteBuf compositeBuffer() {
        return new CompositeByteBuf(defaultAllocator());
    }

    /**
     * Create a new empty {@link CompositeByteBuf} using the specified allocator.
     * <p>
     * Components can be dynamically appended via {@link CompositeByteBuf#addComponent(ByteBuf)}.
     * @param alloc the allocator to use for copy operations
     * @return a new empty CompositeByteBuf
     */
    public static CompositeByteBuf compositeBuffer(ByteBufAllocator alloc) {
        return new CompositeByteBuf(alloc);
    }

    /**
     * Create a new {@link CompositeByteBuf} pre-populated with the given buffers.
     * <p>
        * Each buffer's readable data becomes part of the composite and ownership is transferred
        * to the returned composite. Callers that still need their own references should retain
        * before passing buffers here.
     * @param buffers the buffers to combine
     * @return a new CompositeByteBuf containing all buffers
     */
    public static CompositeByteBuf compositeBuffer(ByteBuf... buffers) {
        CompositeByteBuf composite = new CompositeByteBuf(defaultAllocator());
        if (buffers != null) {
            composite.addComponents(buffers);
        }
        return composite;
    }

    /** Returns whether the send container currently has at least {@code requiredSlots} writable slots. */
    public static boolean hasWritableSlots(ProtoSndData<?> queue, int requiredSlots) {
        if (requiredSlots <= 0) {
            return true;
        }
        return queue != null && queue.slotSize() >= requiredSlots;
    }

    /** Counts how many non-null buffers are present in the array. */
    public static int countBuffers(ByteBuf... buffers) {
        if (buffers == null || buffers.length == 0) {
            return 0;
        }

        int count = 0;
        for (ByteBuf buffer : buffers) {
            if (buffer != null) {
                count++;
            }
        }

        return count;
    }

    /** Releases every non-null buffer in the array. */
    public static void releaseAll(ByteBuf... buffers) {
        if (buffers == null) {
            return;
        }

        for (ByteBuf buffer : buffers) {
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    /** Releases every non-null buffer in the list. */
    public static void releaseAll(List<ByteBuf> buffers) {
        if (buffers == null || buffers.isEmpty()) {
            return;
        }
        for (ByteBuf buffer : buffers) {
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    /**
     * Offers one owned buffer to the destination queue.
     * <p>If the handoff fails, this method releases the buffer locally because ownership did not
     * transfer downstream.</p>
     */
    public static boolean offerOwnedBuffer(ProtoSndQueue<ByteBuf> dst, ByteBuf buffer) {
        if (buffer == null) {
            return true;
        }

        boolean accepted = false;
        try {
            accepted = dst.offerMessage(buffer);
            return accepted;
        } finally {
            if (!accepted) {
                buffer.release();
            }
        }
    }

    /**
     * Offers all owned buffers as one atomic batch.
     * <p>Null buffers are ignored. If the handoff fails, every still-owned buffer is released
     * locally because ownership did not transfer downstream.</p>
     */
    public static boolean offerOwnedBuffers(ProtoSndQueue<ByteBuf> dst, ByteBuf... buffers) {
        if (buffers == null || buffers.length == 0) {
            return true;
        }

        List<ByteBuf> offerList = new ArrayList<>(buffers.length);
        for (ByteBuf buffer : buffers) {
            if (buffer != null) {
                offerList.add(buffer);
            }
        }

        return offerOwnedBuffers(dst, offerList);
    }

    /**
     * Offers all owned buffers in the list as one atomic batch.
     * <p>If the handoff fails, every still-owned buffer is released locally because ownership did
     * not transfer downstream.</p>
     */
    public static boolean offerOwnedBuffers(ProtoSndQueue<ByteBuf> dst, List<ByteBuf> buffers) {
        if (buffers == null || buffers.isEmpty()) {
            return true;
        }

        boolean accepted = false;
        try {
            accepted = dst.offerMessage(buffers);
            return accepted;
        } finally {
            if (!accepted) {
                releaseAll(buffers);
            }
        }
    }

    /** Returns the total readable byte count of all buffers in the list. */
    public static int readableBytes(List<ByteBuf> buffers) {
        int readableBytes = 0;
        for (ByteBuf peek : buffers) {
            readableBytes += peek.readableBytes();
        }
        return readableBytes;
    }

    /** Returns whether the list contains at least {@code readLength} readable bytes. */
    public static boolean readableBytes(List<ByteBuf> buffers, int readLength) {
        return readableBytes(buffers, 0, readLength);
    }

    /** Returns whether the list contains at least {@code readLength} readable bytes from {@code formIdx}. */
    public static boolean readableBytes(List<ByteBuf> buffers, int formIdx, int readLength) {
        long readableBytes = 0;
        for (int i = formIdx; i < buffers.size(); i++) {
            ByteBuf buf = buffers.get(i);
            readableBytes += buf.readableBytes();
            if (readableBytes >= readLength) {
                return true;
            }
        }
        return readableBytes >= readLength;
    }

    /** Resets the reader index of each buffer in the list. */
    public static void resetReader(List<ByteBuf> buffers) {
        if (buffers == null) {
            return;
        }
        for (ByteBuf peek : buffers) {
            peek.resetReader();
        }
    }

    /** Resets the writer index of each buffer in the list. */
    public static void resetWriter(List<ByteBuf> buffers) {
        if (buffers == null) {
            return;
        }
        for (ByteBuf peek : buffers) {
            peek.resetWriter();
        }
    }

    /** Flushes each buffer in order. */
    public static void flush(ByteBuf... buffers) throws IOException {
        if (buffers == null) {
            return;
        }
        for (ByteBuf peek : buffers) {
            peek.flush();
        }
    }

    /**
     * Clear all SmallBufferCache L1 (thread-local) caches for the calling thread.
     * Cached buffers are moved to L2 (global shared) if there is room; otherwise discarded for GC.
     * <p>Call this when a thread is about to be retired, or periodically
     * to keep per-thread memory usage bounded.
     */
    public static void trimSmallBufferCache() {
        SmallBufferCache.trimCurrentThread();
    }

    /**
     * Return the total number of cached objects held by SmallBufferCache L1
     * for the calling thread. Useful for monitoring and diagnostics.
     */
    public static int smallBufferCacheSize() {
        return SmallBufferCache.currentThreadCacheSize();
    }

    /** Lazy-init holder used to avoid circular allocator initialization. */
    private static class AllocatorHolder {
        static final ByteBufAllocator UNPOOLED_HEAP;
        static final ByteBufAllocator POOLED_HEAP;
        static final ByteBufAllocator UNPOOLED_DIRECT;
        static final ByteBufAllocator POOLED_DIRECT;
        static final ByteBufAllocator DEFAULT;

        static {
            String allocType = SystemUtils.getSystemProperty("neta.bytebuf.type", isPooled() ? "pooled" : "unpooled");
            String memType = SystemUtils.getSystemProperty("neta.bytebuf.mem", isDirect() ? "direct" : "heap");
            String sliceSize = SystemUtils.getSystemProperty("neta.bytebuf.sliceSize", String.valueOf(4 * 1024));
            String initialSize = SystemUtils.getSystemProperty("neta.bytebuf.initialSize", String.valueOf(4 * 1024));

            int sliceSizeByDefault = Integer.parseInt(sliceSize);
            int initialCapacityByDefault = Integer.parseInt(initialSize);

            UNPOOLED_HEAP = new BasicByteBufAllocator(false, initialCapacityByDefault, sliceSizeByDefault) {
                @Override
                public boolean isDirect() {
                    return false;
                }

                @Override
                public ByteBuffer jvmBuffer(int capacity) {
                    return ByteBuffer.allocate(capacity);
                }
            };
            POOLED_HEAP = new BasicByteBufAllocator(true, initialCapacityByDefault, sliceSizeByDefault) {
                @Override
                public boolean isDirect() {
                    return false;
                }

                @Override
                public ByteBuffer jvmBuffer(int capacity) {
                    return ByteBuffer.allocate(capacity);
                }
            };
            UNPOOLED_DIRECT = new BasicByteBufAllocator(false, initialCapacityByDefault, sliceSizeByDefault) {
                @Override
                public boolean isDirect() {
                    return true;
                }

                @Override
                public ByteBuffer jvmBuffer(int capacity) {
                    return ByteBuffer.allocateDirect(capacity);
                }
            };
            POOLED_DIRECT = new BasicByteBufAllocator(true, initialCapacityByDefault, sliceSizeByDefault) {
                @Override
                public boolean isDirect() {
                    return true;
                }

                @Override
                public ByteBuffer jvmBuffer(int capacity) {
                    return ByteBuffer.allocateDirect(capacity);
                }
            };

            allocType = allocType.toLowerCase().trim();
            memType = memType.toLowerCase().trim();

            if ("pooled".equals(allocType)) {
                DEFAULT = "direct".equals(memType) ? POOLED_DIRECT : POOLED_HEAP;
            } else {
                DEFAULT = "direct".equals(memType) ? UNPOOLED_DIRECT : UNPOOLED_HEAP;
            }

            logger.debug(String.format("-Dneta.bytebuf.type: %s -Dneta.bytebuf.mem: %s", allocType, memType));
        }
    }
}
