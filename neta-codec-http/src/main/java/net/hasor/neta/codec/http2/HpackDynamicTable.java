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
package net.hasor.neta.codec.http2;

/**
 * HPACK dynamic table as defined in RFC 7541, Section 2.3.2.
 * <p>
 * The dynamic table is a FIFO table with bounded size. New entries are
 * added at the beginning (lowest index), and oldest entries are evicted
 * from the end when the table exceeds its maximum size.
 * <p>
 * Index calculation: dynamic table entries have indices starting at
 * {@code STATIC_TABLE_LENGTH + 1}.
 */
class HpackDynamicTable {
    private HpackHeaderField[] table;
    private int                head;
    private int                tail;
    private int                size;
    private int                count;
    private int                maxSize;

    /**
     * Creates a new dynamic table with the specified maximum size in bytes.
     * @param maxSize maximum table size in bytes (as per SETTINGS_HEADER_TABLE_SIZE)
     */
    public HpackDynamicTable(int maxSize) {
        this.maxSize = maxSize;
        this.table = new HpackHeaderField[16];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
        this.count = 0;
    }

    /** Returns the number of entries in the dynamic table. */
    public int length() {
        return count;
    }

    /** Returns the current size of the dynamic table in bytes. */
    public int size() {
        return size;
    }

    /** Returns the maximum allowed size of the dynamic table in bytes. */
    public int maxSize() {
        return maxSize;
    }

    /**
     * Returns the entry at the given 0-based index (0 = most recently added).
     * @param index 0-based index into the dynamic table
     * @return the header field entry
     */
    public HpackHeaderField get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index: " + index + ", count: " + count);
        }
        int realIdx = (head - index - 1 + table.length) % table.length;
        return table[realIdx];
    }

    /**
     * Adds a new entry to the beginning of the dynamic table.
     * Evicts oldest entries as needed to stay within size limits.
     * @param entry the header field to add
     */
    public void add(HpackHeaderField entry) {
        int entrySize = entry.size();

        // If the entry is larger than the max table size, clear the table (RFC 7541, Section 4.4)
        if (entrySize > maxSize) {
            clear();
            return;
        }

        // Evict entries until there is enough room
        while (size + entrySize > maxSize) {
            evict();
        }

        // Grow array if needed
        if (count == table.length) {
            grow();
        }

        // Add at head
        table[head] = entry;
        head = (head + 1) % table.length;
        count++;
        size += entrySize;
    }

    /**
     * Sets the maximum size of the dynamic table.
     * Evicts entries as needed to comply with the new limit.
     * @param newMaxSize the new maximum size in bytes
     */
    public void setMaxSize(int newMaxSize) {
        if (newMaxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0");
        }
        this.maxSize = newMaxSize;
        while (size > maxSize && count > 0) {
            evict();
        }
    }

    /** Clears all entries from the dynamic table. */
    public void clear() {
        for (int i = 0; i < table.length; i++) {
            table[i] = null;
        }
        head = 0;
        tail = 0;
        count = 0;
        size = 0;
    }

    /** Evicts the oldest entry from the dynamic table. */
    private void evict() {
        if (count == 0) {
            return;
        }
        HpackHeaderField evicted = table[tail];
        table[tail] = null;
        tail = (tail + 1) % table.length;
        count--;
        size -= evicted.size();
    }

    /** Doubles the internal array capacity. */
    private void grow() {
        HpackHeaderField[] newTable = new HpackHeaderField[table.length * 2];
        // Copy entries in order: oldest (tail) to newest (head-1)
        for (int i = 0; i < count; i++) {
            int idx = (tail + i) % table.length;
            newTable[i] = table[idx];
        }
        tail = 0;
        head = count;
        table = newTable;
    }
}
