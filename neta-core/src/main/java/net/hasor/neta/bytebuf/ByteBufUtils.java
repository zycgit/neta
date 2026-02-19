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
import java.util.List;
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.ProtoRcvQueue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ByteBufUtils {
    public static final  ByteBufAllocator DEFAULT_ALLOCATOR;
    public static final  ByteBufAllocator POOLED_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator POOLED_DIRECT_ALLOCATOR;
    public static final  ByteBufAllocator UNPOOLED_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator UNPOOLED_DIRECT_ALLOCATOR;
    public static final  BufferCleaner    CLEANER;
    /** <p>The system default newline character.</p> */
    static final         String           NEWLINE = SystemUtils.getSystemProperty("line.separator", "\n");
    private static final Logger           logger  = Logger.getLogger(ByteBufUtils.class);

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

    private static boolean isPooled() {
        return !SystemUtils.isAndroid();
    }

    private static boolean isDirect() {
        if (SystemUtils.isAndroid()) {
            return false;
        }

        return CLEANER != null;
    }

    public static byte[] toBytes(ByteBuf buf) {
        int available = buf.readableBytes();
        byte[] bytes = new byte[available];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Create a read-only {@link ByteBuf} that presents all queued {@link ByteBuf}
     * messages in the given {@link net.hasor.neta.channel.ProtoRcvQueue ProtoRcvQueue}
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
        return new CompositeByteBuf(DEFAULT_ALLOCATOR);
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
     * Each buffer's readable data becomes part of the composite. Buffers are retained.
     * @param buffers the buffers to combine
     * @return a new CompositeByteBuf containing all buffers
     */
    public static CompositeByteBuf compositeBuffer(ByteBuf... buffers) {
        CompositeByteBuf composite = new CompositeByteBuf(DEFAULT_ALLOCATOR);
        if (buffers != null) {
            composite.addComponents(buffers);
        }
        return composite;
    }

    public static int readableBytes(List<ByteBuf> buffers) {
        int readableBytes = 0;
        for (ByteBuf peek : buffers) {
            readableBytes += peek.readableBytes();
        }
        return readableBytes;
    }

    public static boolean readableBytes(List<ByteBuf> buffers, int readLength) {
        return readableBytes(buffers, 0, readLength);
    }

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

    public static void resetReader(List<ByteBuf> buffers) {
        if (buffers == null) {
            return;
        }
        for (ByteBuf peek : buffers) {
            peek.resetReader();
        }
    }

    public static void resetWriter(List<ByteBuf> buffers) {
        if (buffers == null) {
            return;
        }
        for (ByteBuf peek : buffers) {
            peek.resetWriter();
        }
    }

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
}
