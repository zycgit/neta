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
package net.hasor.neta.codec.http.h3;
/**
 * 用于 HTTP/3 头压缩的 QPACK 动态表，定义见 RFC 9204。
 * <p>
 * 与 HPACK 不同，QPACK 动态表使用绝对索引，并支持跨 stream 的乱序交付。
 * 条目通过绝对索引引用，编码端和解码端各自维护该表的一份副本。
 * <p>
 * 动态表本质上是一个带最大容量限制的 header field FIFO 队列。
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
     * 创建一个新的 QPACK 动态表。
     * @param maxSize 最大字节容量，也就是所有条目大小之和的上限
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

    /**
     * 返回动态表中的条目数量。
     */
    public int length() {
        return count;
    }

    /**
     * 返回当前所有条目的总大小。
     */
    public int currentSize() {
        return currentSize;
    }

    /**
     * 返回允许的最大容量。
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * 返回历史累计插入条目数，也就是绝对索引的基准值。
     */
    public int insertCount() {
        return insertCount;
    }

    /**
     * 按绝对索引获取条目。
     * @param absIndex 绝对索引，0 表示历史上第一个插入的条目
     * @return header field 条目
     * @throws IndexOutOfBoundsException 当条目不可用时抛出
     */
    public QpackHeaderField get(int absIndex) {
        int relIndex = absIndex - (insertCount - count);
        if (relIndex < 0 || relIndex >= count) {
            throw new IndexOutOfBoundsException("QPACK dynamic table: absolute index " + absIndex + " not available");
        }
        return entries[(head + relIndex) % entries.length];
    }

    /**
     * 在动态表尾部插入一个新条目。
     * 如果容量超限，会先驱逐更旧的条目。
     * @param name header 名称
     * @param value header 值
     */
    public void insert(String name, String value) {
        QpackHeaderField field = new QpackHeaderField(name, value);
        int entrySize = field.size();

        // 持续驱逐旧条目，直到有足够空间。
        while (currentSize + entrySize > maxSize && count > 0) {
            evict();
        }

        if (entrySize > maxSize) {
            // 单个条目已经超过表容量，直接清空表并放弃保存该条目。
            clear();
            insertCount++;
            return;
        }

        // 如有必要，扩容底层数组。
        if (count == entries.length) {
            grow();
        }

        entries[tail] = field;
        tail = (tail + 1) % entries.length;
        count++;
        currentSize += entrySize;
        insertCount++;
    }

    /**
     * 驱逐最旧的条目。
     */
    private void evict() {
        if (count == 0)
            return;
        QpackHeaderField evicted = entries[head];
        entries[head] = null;
        head = (head + 1) % entries.length;
        count--;
        currentSize -= evicted.size();
    }

    /**
     * 设置最大表容量；必要时会驱逐旧条目。
     * @param newMaxSize 新容量
     */
    public void setMaxSize(int newMaxSize) {
        this.maxSize = newMaxSize;
        while (currentSize > maxSize && count > 0) {
            evict();
        }
    }

    /**
     * 清空表中的所有条目。
     */
    public void clear() {
        head = 0;
        tail = 0;
        count = 0;
        currentSize = 0;
        entries = new QpackHeaderField[16];
    }

    /**
     * 将内部数组容量扩展为原来的两倍。
     */
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
