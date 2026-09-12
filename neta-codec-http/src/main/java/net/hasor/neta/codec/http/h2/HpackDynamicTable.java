/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
/**
 * HPACK dynamic table as defined by RFC 7541 Section 2.3.2.
 * <p>
 * The dynamic table is a capacity-bounded FIFO table. New entries are inserted at the front
 * (lowest index), and the oldest entries are evicted from the tail when the capacity limit is
 * exceeded.
 * <p>
 * Dynamic table entries are indexed starting at {@code STATIC_TABLE_LENGTH + 1}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class HpackDynamicTable {
    private HpackHeaderField[] table;
    private int                head;
    private int                tail;
    private int                size;
    private int                count;
    private int                maxSize;

    /**
     * Creates a new dynamic table with the specified maximum byte capacity.
     * @param maxSize the maximum table capacity, with the same semantics as SETTINGS_HEADER_TABLE_SIZE
     */
    public HpackDynamicTable(int maxSize) {
        this.maxSize = maxSize;
        this.table = new HpackHeaderField[16];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
        this.count = 0;
    }

    /**
     * Returns the number of entries currently stored in the dynamic table.
     */
    public int length() {
        return count;
    }

    /**
     * Returns the current byte size used by the dynamic table.
     */
    public int size() {
        return size;
    }

    /**
     * Returns the maximum number of bytes allowed for the dynamic table.
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * Returns the entry at the specified zero-based index, where 0 refers to the most recently inserted entry.
     * @param index the zero-based index within the dynamic table
     * @return the header field entry
     */
    public HpackHeaderField get(int index) {
        if (index < 0 || index >= count) {
            throw new HpackDecodingException("HPACK: invalid dynamic table index " + index + " (count=" + count + ")");
        }
        int realIdx = (head - index - 1 + table.length) % table.length;
        return table[realIdx];
    }

    /**
     * Adds a new entry to the head of the dynamic table.
     * Older entries are evicted first if needed to satisfy the capacity limit.
     * @param entry the header field to add
     */
    public void add(HpackHeaderField entry) {
        int entrySize = entry.size();

        // If a single entry exceeds the table capacity, RFC 7541 Section 4.4 requires the table to be cleared.
        if (entrySize > maxSize) {
            clear();
            return;
        }

        // Keep evicting old entries until enough space is available.
        while (size + entrySize > maxSize) {
            evict();
        }

        // Grow the backing array if necessary.
        if (count == table.length) {
            grow();
        }

        // Insert the new entry at the head.
        table[head] = entry;
        head = (head + 1) % table.length;
        count++;
        size += entrySize;
    }

    /**
     * Sets the maximum capacity of the dynamic table.
     * Older entries are evicted if necessary to satisfy the new limit.
     * @param newMaxSize the new maximum byte capacity
     */
    public void setMaxSize(int newMaxSize) {
        if (newMaxSize < 0) {
            throw new HpackDecodingException("HPACK: dynamic table size update must be non-negative: " + newMaxSize);
        }
        this.maxSize = newMaxSize;
        while (size > maxSize && count > 0) {
            evict();
        }
    }

    /**
     * Removes all entries from the dynamic table.
     */
    public void clear() {
        for (int i = 0; i < table.length; i++) {
            table[i] = null;
        }
        head = 0;
        tail = 0;
        count = 0;
        size = 0;
    }

    /**
     * Evicts the oldest entry from the table.
     */
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

    /**
     * Expands the internal array capacity to twice its current size.
     */
    private void grow() {
        HpackHeaderField[] newTable = new HpackHeaderField[table.length * 2];
        // Copy entries in order, from the oldest entry (tail) to the newest entry (head - 1).
        for (int i = 0; i < count; i++) {
            int idx = (tail + i) % table.length;
            newTable[i] = table[idx];
        }
        tail = 0;
        head = count;
        table = newTable;
    }
}
