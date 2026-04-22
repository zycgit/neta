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
package net.hasor.neta.codec.http;
import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
/**
 * Segmented storage for HTTP header entries that avoids full-array copies on growth.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-22
 */
final class HeaderEntryStore extends AbstractList<DefaultHttpHeaderEntry> {
    private static final int                        SEGMENT_SHIFT = 4;
    private static final int                        SEGMENT_SIZE  = 1 << SEGMENT_SHIFT;
    private static final int                        SEGMENT_MASK  = SEGMENT_SIZE - 1;
    private static final DefaultHttpHeaderEntry[][] EMPTY         = new DefaultHttpHeaderEntry[0][];
    private DefaultHttpHeaderEntry[][]              segments      = EMPTY;
    private int                                     size;

    HeaderEntryStore() {
        this(0);
    }

    HeaderEntryStore(int initialCapacity) {
        this.ensureAppendCapacity(initialCapacity);
    }

    HeaderEntryStore(List<DefaultHttpHeaderEntry> entries) {
        this(entries != null ? entries.size() : 0);
        if (entries != null && !entries.isEmpty()) {
            this.addAll(entries);
        }
    }

    void ensureAppendCapacity(int additional) {
        if (additional <= 0) {
            return;
        }
        int requiredSize = this.size + additional;
        int requiredSegments = segmentIndex(requiredSize - 1) + 1;
        if (requiredSegments > this.segments.length) {
            int newLength = Math.max(requiredSegments, Math.max(2, this.segments.length << 1));
            DefaultHttpHeaderEntry[][] newSegments = new DefaultHttpHeaderEntry[newLength][];
            System.arraycopy(this.segments, 0, newSegments, 0, this.segments.length);
            this.segments = newSegments;
        }
        for (int i = 0; i < requiredSegments; i++) {
            if (this.segments[i] == null) {
                this.segments[i] = new DefaultHttpHeaderEntry[SEGMENT_SIZE];
            }
        }
    }

    @Override
    public DefaultHttpHeaderEntry get(int index) {
        checkIndex(index);
        return this.segments[segmentIndex(index)][segmentOffset(index)];
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public boolean add(DefaultHttpHeaderEntry entry) {
        int index = this.size;
        int segment = segmentIndex(index);
        if (segment >= this.segments.length || this.segments[segment] == null) {
            this.ensureAppendCapacity(1);
            segment = segmentIndex(index);
        }
        this.segments[segment][segmentOffset(index)] = entry;
        this.size++;
        this.modCount++;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends DefaultHttpHeaderEntry> collection) {
        if (collection == null || collection.isEmpty()) {
            return false;
        }
        int incoming = collection.size();
        int requiredSize = this.size + incoming;
        int lastSegment = segmentIndex(requiredSize - 1);
        if (lastSegment >= this.segments.length || this.segments[lastSegment] == null) {
            this.ensureAppendCapacity(incoming);
        }
        for (DefaultHttpHeaderEntry entry : collection) {
            this.segments[segmentIndex(this.size)][segmentOffset(this.size)] = entry;
            this.size++;
        }
        this.modCount++;
        return true;
    }

    @Override
    public DefaultHttpHeaderEntry set(int index, DefaultHttpHeaderEntry element) {
        checkIndex(index);
        int segment = segmentIndex(index);
        int offset = segmentOffset(index);
        DefaultHttpHeaderEntry previous = this.segments[segment][offset];
        this.segments[segment][offset] = element;
        return previous;
    }

    @Override
    public DefaultHttpHeaderEntry remove(int index) {
        checkIndex(index);
        DefaultHttpHeaderEntry removed = this.get(index);
        for (int i = index + 1; i < this.size; i++) {
            this.setDirect(i - 1, this.get(i));
        }
        this.size--;
        this.setDirect(this.size, null);
        this.modCount++;
        return removed;
    }

    @Override
    public void clear() {
        for (int i = 0; i < this.size; i++) {
            this.setDirect(i, null);
        }
        this.size = 0;
        this.modCount++;
    }

    private void setDirect(int index, DefaultHttpHeaderEntry entry) {
        if (index < 0) {
            return;
        }
        int segment = segmentIndex(index);
        if (segment >= this.segments.length || this.segments[segment] == null) {
            return;
        }
        this.segments[segment][segmentOffset(index)] = entry;
    }

    private static int segmentIndex(int index) {
        return index >> SEGMENT_SHIFT;
    }

    private static int segmentOffset(int index) {
        return index & SEGMENT_MASK;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + this.size);
        }
    }
}
