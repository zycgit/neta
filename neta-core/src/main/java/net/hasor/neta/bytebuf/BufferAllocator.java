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
 * Low-level factory for allocating raw {@link java.nio.ByteBuffer} instances.
 * <p>This interface abstracts the allocation strategy for AIO swap buffers (e.g., TCP).
 * Callers always go through {@link ByteBufAllocator#jvmBuffer(int)} which delegates here.
 * <p>Implementations decide whether to allocate heap or direct
 * ({@link java.nio.ByteBuffer#allocateDirect}) memory:
 * <ul>
 *   <li>Heap buffers are cheaper to allocate but require a kernel → user-space copy
 *       for every AIO I/O operation on most OS/JVM combinations.</li>
 *   <li>Direct buffers avoid the extra copy but must be freed explicitly via
 *       {@link BufferCleaner} to avoid off-heap memory leaks, because the JVM
 *       only releases them at GC time otherwise.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see ByteBufAllocator
 * @see BufferCleaner
 */
public interface BufferAllocator {
    /** Creates a ByteBuffer with the requested capacity. */
    ByteBuffer jvmBuffer(int capacity);
}
