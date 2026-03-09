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