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
package net.hasor.neta.codec.http3;

/**
 * QPACK dynamic table for HTTP/3 header compression (RFC 9204).
 * <p>
 * Unlike HPACK, QPACK's dynamic table uses absolute indices and supports
 * out-of-order delivery across streams. Entries are referenced by absolute
 * index, and the encoder and decoder maintain separate copies of this table.
 * <p>
 * The dynamic table is a FIFO queue of header fields bounded by a maximum size.
 */
public class QpackDynamicTable {
    private QpackHeaderField[] entries;
    private int                head;
    private int                tail;
    private int                count;
    private int                currentSize;
    private int                maxSize;
    private int                insertCount;

    /**
     * Creates a new QPACK dynamic table.
     * @param maxSize the maximum size in bytes (sum of entry sizes)
     */
    public QpackDynamicTable(int maxSize) {
        this.maxSize = maxSize;
        this.entries = new QpackHeaderField[16];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.currentSize = 0;
        this.insertCount = 0;
    }

    /** Returns the number of entries in the dynamic table. */
    public int length() {
        return count;
    }

    /** Returns the current total size of all entries. */
    public int currentSize() {
        return currentSize;
    }

    /** Returns the maximum allowed size. */
    public int maxSize() {
        return maxSize;
    }

    /** Returns the total number of entries ever inserted (absolute index base). */
    public int insertCount() {
        return insertCount;
    }

    /**
     * Gets an entry by absolute index.
     * @param absIndex the absolute index (0 = first ever inserted)
     * @return the header field
     * @throws IndexOutOfBoundsException if not available
     */
    public QpackHeaderField get(int absIndex) {
        int relIndex = absIndex - (insertCount - count);
        if (relIndex < 0 || relIndex >= count) {
            throw new IndexOutOfBoundsException("QPACK dynamic table: absolute index " + absIndex + " not available");
        }
        return entries[(head + relIndex) % entries.length];
    }

    /**
     * Inserts a new entry at the end of the dynamic table.
     * Evicts older entries if needed to stay within size limits.
     * @param name the header name
     * @param value the header value
     */
    public void insert(String name, String value) {
        QpackHeaderField field = new QpackHeaderField(name, value);
        int entrySize = field.size();

        // Evict entries until there's room
        while (currentSize + entrySize > maxSize && count > 0) {
            evict();
        }

        if (entrySize > maxSize) {
            // Entry is larger than table, just clear
            clear();
            insertCount++;
            return;
        }

        // Grow array if needed
        if (count == entries.length) {
            grow();
        }

        entries[tail] = field;
        tail = (tail + 1) % entries.length;
        count++;
        currentSize += entrySize;
        insertCount++;
    }

    /** Evicts the oldest entry from the table. */
    private void evict() {
        if (count == 0)
            return;
        QpackHeaderField evicted = entries[head];
        entries[head] = null;
        head = (head + 1) % entries.length;
        count--;
        currentSize -= evicted.size();
    }

    /** Sets the maximum table size, evicting entries as necessary. */
    public void setMaxSize(int newMaxSize) {
        this.maxSize = newMaxSize;
        while (currentSize > maxSize && count > 0) {
            evict();
        }
    }

    /** Clears all entries from the table. */
    public void clear() {
        head = 0;
        tail = 0;
        count = 0;
        currentSize = 0;
        entries = new QpackHeaderField[16];
    }

    /** Doubles the internal array capacity. */
    private void grow() {
        QpackHeaderField[] newEntries = new QpackHeaderField[entries.length * 2];
        for (int i = 0; i < count; i++) {
            newEntries[i] = entries[(head + i) % entries.length];
        }
        entries = newEntries;
        head = 0;
        tail = count;
    }
}
