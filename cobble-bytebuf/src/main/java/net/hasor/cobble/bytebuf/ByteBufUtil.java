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
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;

import java.util.Locale;

/**
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public class ByteBufUtil {
    private static final Logger           logger = Logger.getLogger(ByteBufUtil.class);
    public static final  ByteBufAllocator DEFAULT_ALLOCATOR;
    public static final  ByteBufAllocator DEFAULT_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator DEFAULT_DIRECT_ALLOCATOR;
    public static final  ByteBufAllocator DEFAULT_POOLED_HEAP_ALLOCATOR;
    public static final  ByteBufAllocator DEFAULT_POOLED_DIRECT_ALLOCATOR;
    public static final  Cleaner          CLEANER;

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
        String allocType = SystemUtils.getSystemProperty("cobble.bytebuf.type", isPooled() ? "pooled" : "unpooled");
        String memType = SystemUtils.getSystemProperty("cobble.bytebuf.mem", isDirect() ? "direct" : "heap");
        String sliceSize = SystemUtils.getSystemProperty("cobble.bytebuf.sliceSize", String.valueOf(16 * 1024));
        String initialSize = SystemUtils.getSystemProperty("cobble.bytebuf.initialSize", String.valueOf(4 * 1024));

        int sliceSizeByDefault = Integer.parseInt(sliceSize);
        int initialCapacityByDefault = Integer.parseInt(initialSize);
        DEFAULT_HEAP_ALLOCATOR = new HeapByteBufAllocator(initialCapacityByDefault, sliceSizeByDefault);
        DEFAULT_POOLED_HEAP_ALLOCATOR = new PooledHeapByteBufAllocator(initialCapacityByDefault, sliceSizeByDefault);
        DEFAULT_DIRECT_ALLOCATOR = new DirectByteBufAllocator(initialCapacityByDefault, sliceSizeByDefault);
        DEFAULT_POOLED_DIRECT_ALLOCATOR = new PooledDirectByteBufAllocator(initialCapacityByDefault, sliceSizeByDefault);

        allocType = allocType.toLowerCase(Locale.US).trim();
        memType = memType.toLowerCase(Locale.US).trim();

        if ("unpooled".equals(allocType)) {
            if ("heap".equals(memType)) {
                logger.debug("-Dcobble.bytebuf.type: unpooled -Dcobble.bytebuf.mem: heap");
                DEFAULT_ALLOCATOR = DEFAULT_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug("-Dcobble.bytebuf.type: unpooled -Dcobble.bytebuf.mem: direct");
                DEFAULT_ALLOCATOR = DEFAULT_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dcobble.bytebuf.type: unpooled -Dcobble.bytebuf.mem: heap (unknown: %s)", memType));
                DEFAULT_ALLOCATOR = DEFAULT_HEAP_ALLOCATOR;
            }
        } else if ("pooled".equals(allocType)) {
            if ("heap".equals(memType)) {
                logger.debug("-Dcobble.bytebuf.type: pooled -Dcobble.bytebuf.mem: heap");
                DEFAULT_ALLOCATOR = DEFAULT_POOLED_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug("-Dcobble.bytebuf.type: pooled -Dcobble.bytebuf.mem: direct");
                DEFAULT_ALLOCATOR = DEFAULT_POOLED_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dcobble.bytebuf.type: pooled -Dcobble.bytebuf.mem: heap (unknown: %s)", memType));
                DEFAULT_ALLOCATOR = DEFAULT_POOLED_HEAP_ALLOCATOR;
            }
        } else {
            if ("heap".equals(memType)) {
                logger.debug(String.format("-Dcobble.bytebuf.type: pooled (unknown: %s) -Dcobble.bytebuf.mem: heap", allocType));
                DEFAULT_ALLOCATOR = DEFAULT_HEAP_ALLOCATOR;
            } else if ("direct".equals(memType)) {
                logger.debug(String.format("-Dcobble.bytebuf.type: pooled (unknown: %s) -Dcobble.bytebuf.mem: direct", allocType));
                DEFAULT_ALLOCATOR = DEFAULT_DIRECT_ALLOCATOR;
            } else {
                logger.debug(String.format("-Dcobble.bytebuf.type: pooled (unknown: %s) -Dcobble.bytebuf.mem: heap (unknown: %s)", allocType, memType));
                DEFAULT_ALLOCATOR = DEFAULT_HEAP_ALLOCATOR;
            }
        }

        if (SystemUtils.getJavaVersion() >= 9) {
            CLEANER = CleanerJava9.isSupported() ? new CleanerJava9() : null;
        } else {
            CLEANER = CleanerJava6.isSupported() ? new CleanerJava6() : null;
        }
    }
}
