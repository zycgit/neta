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
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ByteBufUtils {
    private static final Logger           logger = Logger.getLogger(ByteBufUtils.class);
    public static final  ByteBufAllocator DEFAULT_ALLOCATOR;
    public static final  ByteBufAllocator POOLED_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator POOLED_DIRECT_ALLOCATOR;
    public static final  ByteBufAllocator UNPOOLED_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator UNPOOLED_DIRECT_ALLOCATOR;
    public static final  BufferCleaner    CLEANER;

    private static boolean isPooled() {
        return !SystemUtils.isAndroid();
    }

    private static boolean isDirect() {
        if (SystemUtils.isAndroid()) {
            return false;
        }

        return CLEANER != null;
    }

    // ensure DEFAULT
    static {
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

        if (SystemUtils.getJavaVersion() >= 9) {
            CLEANER = BufferCleanerJava9.isSupported() ? new BufferCleanerJava9() : null;
        } else {
            CLEANER = BufferCleanerJava6.isSupported() ? new BufferCleanerJava6() : null;
        }
    }

    /** <p>The system default newline character.</p> */
    static final String NEWLINE = SystemUtils.getSystemProperty("line.separator", "\n");

    public static byte[] toBytes(ByteBuf buf) {
        int available = buf.readableBytes();
        byte[] bytes = new byte[available];
        buf.readBytes(bytes);
        return bytes;
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
}
