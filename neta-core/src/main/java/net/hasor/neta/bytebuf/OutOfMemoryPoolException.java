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
/**
 * Signals that a pooled allocation request cannot be satisfied by the current
 * {@link BufferPool} configuration.
 * <p>In the current implementation this is thrown mainly in two situations:
 * <ul>
 *   <li>the requested capacity is larger than a single {@link PageChunkPool}
 *       can represent; or</li>
 *   <li>the pool wants to allocate a new chunk but has already reached its
 *       configured {@code maximumChunkCount}.</li>
 * </ul>
 * <p>Callers that catch this exception typically need to fall back to an
 * unpooled buffer, reduce the requested size, or raise the pool limit.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see BufferPool
 * @see BufferPoolUtils
 */
public class OutOfMemoryPoolException extends RuntimeException {
    public OutOfMemoryPoolException(String s) {
        super(s);
    }
}