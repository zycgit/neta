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
 * Descriptor for a contiguous page interval inside a {@link PageChunkPool}.
 * <p>A page is the smallest allocation unit managed by the pool. The range is
 * described by the owning memory block address, the inclusive start page, the
 * inclusive end page, and the page size.
 * <p>The represented byte window therefore begins at
 * {@code getFromPage() * getPageSize()} and ends at
 * {@code (getToPage() + 1) * getPageSize() - 1} within the memory block
 * identified by {@link #getMemAddress()}.
 * <p>This interface is shared by free page descriptors and active split
 * allocations.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see PageChunk
 * @see PageChunkSplit
 * @see PageChunkPool
 */
interface PageRange {
    /** The memory address used to mark memory blocks */
    int getMemAddress();

    /** The allocated start page */
    int getFromPage();

    /** The allocated eof page */
    int getToPage();

    /** The pageSize of this buffer. */
    int getPageSize();
}