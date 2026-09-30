/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Compact, growable storage for HTTP header entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-22
 */
final class HeaderEntryStore extends AbstractList<DefaultHttpHeaderEntry> implements RandomAccess {
    private static final RecycleObjectPool<Storage> RECYCLER = new RecycleObjectPool<>(Storage::new, Storage::acquire, Storage::recycle, Storage::retainedWeight, 32 * 1024, 256 * 1024);
    private              Storage                    storage;
    private final        int                        initialCapacity;

    HeaderEntryStore() {
        this(0);
    }

    HeaderEntryStore(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity: " + initialCapacity);
        }
        this.initialCapacity = initialCapacity;
    }

    HeaderEntryStore(List<DefaultHttpHeaderEntry> entries) {
        this(entries != null ? entries.size() : 0);
        if (entries != null && !entries.isEmpty()) {
            this.addAll(entries);
        }
    }

    void ensureAppendCapacity(int additional) {
        if (additional > 0) {
            long required = (long) this.size() + additional;
            if (required > Integer.MAX_VALUE / 4) {
                throw new OutOfMemoryError("Too many header entries");
            }
            this.ensureCapacity((int) required);
        }
    }

    private void ensureCapacity(int required) {
        if (required > Integer.MAX_VALUE / 4) {
            throw new OutOfMemoryError("Too many header entries");
        }
        if (required == 0) {
            return;
        }
        if (this.storage == null) {
            this.storage = RECYCLER.get();
            required = Math.max(required, this.initialCapacity);
        }
        this.storage.ensureCapacity(required);
    }

    @Override
    public int size() {
        return this.storage == null ? 0 : this.storage.used;
    }

    @Override
    public DefaultHttpHeaderEntry get(int index) {
        Objects.checkIndex(index, this.size());
        Storage s = this.storage;
        int data = index * 3;
        int range = index * 4;
        if (s.ranges[range + 1] == 0) {
            return (DefaultHttpHeaderEntry) s.data[data];
        }
        ByteBuf source = (ByteBuf) s.data[data + 2];
        if (source != null) {
            source.retain();
        }
        DefaultHttpHeaderEntry entry;
        try {
            entry = DefaultHttpHeaderEntry.materializeDecodedEntry(source, s.ranges[range], s.ranges[range + 1], s.ranges[range + 2], s.ranges[range + 3], (String) s.data[data], (String) s.data[data + 1]);
        } catch (RuntimeException | Error e) {
            if (source != null) {
                source.release();
            }
            throw e;
        }
        if (source != null) {
            s.releaseSource(source);
        }
        s.data[data] = entry;
        s.data[data + 1] = null;
        s.data[data + 2] = null;
        s.ranges[range + 1] = 0;
        s.hasEntries = true;
        return entry;
    }

    @Override
    public boolean add(DefaultHttpHeaderEntry entry) {
        this.ensureAppendCapacity(1);
        this.setEntry(this.storage.used++, entry);
        this.modCount++;
        return true;
    }

    @Override
    public void add(int index, DefaultHttpHeaderEntry entry) {
        this.checkPosition(index);
        this.ensureAppendCapacity(1);
        Storage s = this.storage;
        int moving = s.used - index;
        System.arraycopy(s.data, index * 3, s.data, (index + 1) * 3, moving * 3);
        System.arraycopy(s.ranges, index * 4, s.ranges, (index + 1) * 4, moving * 4);
        this.setEntry(index, entry);
        s.used++;
        this.modCount++;
    }

    @Override
    public DefaultHttpHeaderEntry set(int index, DefaultHttpHeaderEntry entry) {
        DefaultHttpHeaderEntry previous = this.get(index);
        this.setEntry(index, entry);
        return previous;
    }

    @Override
    public DefaultHttpHeaderEntry remove(int index) {
        DefaultHttpHeaderEntry previous = this.get(index);
        Storage s = this.storage;
        int moving = --s.used - index;
        System.arraycopy(s.data, (index + 1) * 3, s.data, index * 3, moving * 3);
        System.arraycopy(s.ranges, (index + 1) * 4, s.ranges, index * 4, moving * 4);
        Arrays.fill(s.data, s.used * 3, (s.used + 1) * 3, null);
        this.modCount++;
        return previous;
    }

    @Override
    public void clear() {
        Storage previous = this.storage;
        this.storage = null;
        this.modCount++;
        if (previous != null) {
            RECYCLER.recycle(previous);
        }
    }

    @Override
    public boolean addAll(int index, Collection<? extends DefaultHttpHeaderEntry> entries) {
        this.checkPosition(index);
        Object[] incoming = entries.toArray();
        if (incoming.length == 0) {
            return false;
        }
        this.ensureAppendCapacity(incoming.length);
        Storage s = this.storage;
        int moving = s.used - index;
        System.arraycopy(s.data, index * 3, s.data, (index + incoming.length) * 3, moving * 3);
        System.arraycopy(s.ranges, index * 4, s.ranges, (index + incoming.length) * 4, moving * 4);
        for (int i = 0; i < incoming.length; i++) {
            this.setEntry(index + i, (DefaultHttpHeaderEntry) incoming[i]);
        }
        s.used += incoming.length;
        this.modCount++;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends DefaultHttpHeaderEntry> entries) {
        if (entries instanceof HeaderEntryStore source && entries != this) {
            int incoming = source.size();
            this.ensureAppendCapacity(incoming);
            for (int i = 0; i < incoming; i++) {
                this.add(source.get(i));
            }
            return incoming != 0;
        }
        return this.addAll(this.size(), entries);
    }

    // The caller lends a stable view; this store owns one reference per distinct view.
    void addDecoded(ByteBuf source, int nameOffset, int nameLength, int valueOffset, int valueLength) {
        if (source == null || nameOffset < 0 || nameLength <= 0 || valueOffset < 0 || valueLength < 0) {
            throw new IllegalArgumentException("Invalid decoded header range");
        }
        this.ensureAppendCapacity(1);
        Storage s = this.storage;
        s.appendSource(source);
        int data = s.used * 3;
        int range = s.used * 4;
        s.data[data] = null;
        s.data[data + 1] = valueLength == 0 ? "" : null;
        s.data[data + 2] = source;
        s.ranges[range] = nameOffset;
        s.ranges[range + 1] = nameLength;
        s.ranges[range + 2] = valueOffset;
        s.ranges[range + 3] = valueLength;
        s.used++;
        this.modCount++;
    }

    int findFirstIndex(String name) {
        Storage s = this.storage;
        if (name == null || s == null) {
            return -1;
        }
        if (!s.hasEntries) {
            return findFirstDecodedIndex(s, name);
        }
        for (int i = 0; i < s.used; i++) {
            if (this.matchesName(i, name)) {
                return i;
            }
        }
        return -1;
    }

    String findFirstValue(String name) {
        Storage s = this.storage;
        if (name == null || s == null) {
            return null;
        }
        if (!s.hasEntries) {
            int index = findFirstDecodedIndex(s, name);
            return index >= 0 ? readValue(s, index * 3, index * 4) : null;
        }
        for (int i = 0, data = 0, range = 0; i < s.used; i++, data += 3, range += 4) {
            if (s.ranges[range + 1] == 0) {
                DefaultHttpHeaderEntry entry = (DefaultHttpHeaderEntry) s.data[data];
                if (entry.matchesName(name)) {
                    return entry.getValue();
                }
            } else {
                String resolved = (String) s.data[data];
                if (resolved != null ? HttpCharSequences.equalsIgnoreCase(resolved, name) : HttpCharSequences.equalsIgnoreCase((ByteBuf) s.data[data + 2], s.ranges[range], s.ranges[range + 1], name)) {
                    return readValue(s, data, range);
                }
            }
        }
        return null;
    }

    private static int findFirstDecodedIndex(Storage s, String name) {
        Object[] data = s.data;
        int[] ranges = s.ranges;
        int nameLength = name.length();
        for (int i = 0, dataIndex = 0, range = 0; i < s.used; i++, dataIndex += 3, range += 4) {
            String resolved = (String) data[dataIndex];
            if (resolved != null) {
                if (HttpCharSequences.equalsIgnoreCase(resolved, name)) {
                    return i;
                }
            } else if (ranges[range + 1] == nameLength && HttpCharSequences.equalsIgnoreCase((ByteBuf) data[dataIndex + 2], ranges[range], nameLength, name)) {
                return i;
            }
        }
        return -1;
    }

    boolean matchesName(int index, String name) {
        Storage s = this.storage;
        int range = index * 4;
        int data = index * 3;
        if (s.ranges[range + 1] == 0) {
            return ((DefaultHttpHeaderEntry) s.data[data]).matchesName(name);
        }
        String resolved = (String) s.data[data];
        return resolved != null ? HttpCharSequences.equalsIgnoreCase(resolved, name) : HttpCharSequences.equalsIgnoreCase((ByteBuf) s.data[data + 2], s.ranges[range], s.ranges[range + 1], name);
    }

    String getName(int index) {
        Storage s = this.storage;
        int data = index * 3;
        int range = index * 4;
        if (s.ranges[range + 1] == 0) {
            return ((DefaultHttpHeaderEntry) s.data[data]).getName();
        }
        String value = (String) s.data[data];
        if (value == null) {
            value = readSource((ByteBuf) s.data[data + 2], s.ranges[range], s.ranges[range + 1]);
            s.data[data] = value;
            releaseResolvedSource(s, data);
        }
        return value;
    }

    String getValue(int index) {
        Storage s = this.storage;
        int data = index * 3;
        int range = index * 4;
        if (s.ranges[range + 1] == 0) {
            return ((DefaultHttpHeaderEntry) s.data[data]).getValue();
        }
        return readValue(s, data, range);
    }

    private static String readValue(Storage s, int data, int range) {
        String value = (String) s.data[data + 1];
        if (value == null) {
            value = readSource((ByteBuf) s.data[data + 2], s.ranges[range + 2], s.ranges[range + 3]);
            s.data[data + 1] = value;
            releaseResolvedSource(s, data);
        }
        return value;
    }

    long parseLongValue(int index) {
        Storage s = this.storage;
        int range = index * 4;
        int data = index * 3;
        if (s.ranges[range + 1] == 0) {
            return ((DefaultHttpHeaderEntry) s.data[data]).parseLongValue();
        }
        String value = (String) s.data[data + 1];
        return value != null ? HttpCharSequences.parseLong(value) : HttpCharSequences.parseLong((ByteBuf) s.data[data + 2], s.ranges[range + 2], s.ranges[range + 3]);
    }

    void transferFrom(HeaderEntryStore source) {
        if (source == this) {
            throw new IllegalArgumentException("Cannot transfer a store to itself");
        }
        int incoming = source.size();
        if (incoming == 0) {
            return;
        }
        if (this.storage == null) {
            this.storage = source.storage;
            source.storage = null;
            this.modCount++;
            source.modCount++;
            return;
        }
        this.ensureAppendCapacity(incoming);
        Storage target = this.storage;
        Storage from = source.storage;
        target.ensureSourceCapacity(target.extraUsed + from.extraUsed + (from.source == null ? 0 : 1));
        target.adoptSources(from);
        System.arraycopy(source.storage.data, 0, target.data, target.used * 3, incoming * 3);
        System.arraycopy(source.storage.ranges, 0, target.ranges, target.used * 4, incoming * 4);
        target.used += incoming;
        target.hasEntries |= from.hasEntries;
        this.modCount++;
        source.clear();
    }

    // Exposed entries own their references; clear() releases the block-owned raw sources.
    void releaseEntries() {
        Storage s = this.storage;
        if (s == null || !s.hasEntries) {
            return;
        }
        for (int i = 0; i < s.used; i++) {
            int data = i * 3;
            if (s.ranges[i * 4 + 1] == 0) {
                DefaultHttpHeaderEntry entry = (DefaultHttpHeaderEntry) s.data[data];
                if (entry != null) {
                    entry.release();
                }
            }
        }
    }

    private void setEntry(int index, DefaultHttpHeaderEntry entry) {
        int data = index * 3;
        this.storage.data[data] = entry;
        this.storage.data[data + 1] = null;
        this.storage.data[data + 2] = null;
        this.storage.ranges[index * 4 + 1] = 0;
        this.storage.hasEntries = true;
    }

    private void checkPosition(int index) {
        if (index < 0 || index > this.size()) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static void releaseResolvedSource(Storage s, int data) {
        if (s.data[data] != null && s.data[data + 1] != null && s.data[data + 2] != null) {
            s.releaseSource((ByteBuf) s.data[data + 2]);
            s.data[data + 2] = null;
        }
    }

    private static String readSource(ByteBuf source, int offset, int length) {
        if (source == null || source.isFree()) {
            return "";
        }
        try {
            return source.getString(offset, length, StandardCharsets.US_ASCII);
        } catch (IllegalStateException e) {
            return "";
        }
    }

    // Raw rows borrow from this block. Exposed entries acquire independent references.
    private static final class Storage {
        private Object[]  data;
        private int[]     ranges;
        private int       used;
        // Conservative until recycle, so removals need no extra accounting.
        private boolean   hasEntries;
        private ByteBuf   source;
        private int       sourceUses;
        private ByteBuf[] extraSources;
        private int[]     extraUses;
        private int       extraUsed;
        private int       retainedWeight = 128;

        void acquire() {
            this.used = 0;
        }

        void recycle() {
            this.releaseSources();
            if (this.data != null) {
                Arrays.fill(this.data, 0, this.used * 3, null);
            }
            this.used = 0;
            this.hasEntries = false;
        }

        int retainedWeight() {
            return this.retainedWeight;
        }

        private void updateRetainedWeight() {
            // Conservative capacity charge, including uncompressed references and object headers.
            long weight = 128L + (this.data == null ? 0 : this.data.length * 8L + this.ranges.length * 4L) + (this.extraSources == null ? 0 : 32L + this.extraSources.length * 12L);
            this.retainedWeight = (int) Math.min(Integer.MAX_VALUE, weight);
        }

        void appendSource(ByteBuf source) {
            if (this.source == source) {
                this.sourceUses++;
            } else if (this.source == null && this.extraUsed == 0) {
                this.source = source.retain();
                this.sourceUses = 1;
            } else {
                this.addSource(source, 1, false);
            }
        }

        private void addSource(ByteBuf source, int uses, boolean owned) {
            if (this.source == source) {
                this.sourceUses += uses;
                if (owned) {
                    source.release();
                }
                return;
            }
            if (this.source == null && this.extraUsed == 0) {
                this.source = owned ? source : source.retain();
                this.sourceUses = uses;
                return;
            }
            int empty = -1;
            for (int i = 0; i < this.extraUsed; i++) {
                if (this.extraSources[i] == source) {
                    this.extraUses[i] += uses;
                    if (owned) {
                        source.release();
                    }
                    return;
                }
                if (this.extraSources[i] == null) {
                    empty = i;
                }
            }
            if (this.source == null) {
                this.source = owned ? source : source.retain();
                this.sourceUses = uses;
                return;
            }
            int index = empty >= 0 ? empty : this.extraUsed;
            this.ensureSourceCapacity(index + 1);
            this.extraSources[index] = owned ? source : source.retain();
            this.extraUses[index] = uses;
            this.extraUsed = Math.max(this.extraUsed, index + 1);
        }

        void releaseSource(ByteBuf source) {
            if (this.source == source) {
                if (--this.sourceUses == 0) {
                    this.source = null;
                    source.release();
                }
                return;
            }
            for (int i = 0; i < this.extraUsed; i++) {
                if (this.extraSources[i] == source) {
                    if (--this.extraUses[i] == 0) {
                        this.extraSources[i] = null;
                        source.release();
                    }
                    return;
                }
            }
            throw new IllegalStateException("Header source is not owned");
        }

        void releaseSources() {
            ByteBuf primary = this.source;
            this.source = null;
            this.sourceUses = 0;
            if (primary != null && !primary.isFree()) {
                primary.release();
            }
            for (int i = 0; i < this.extraUsed; i++) {
                ByteBuf extra = this.extraSources[i];
                this.extraSources[i] = null;
                this.extraUses[i] = 0;
                if (extra != null && !extra.isFree()) {
                    extra.release();
                }
            }
            this.extraUsed = 0;
        }

        void adoptSources(Storage from) {
            if (from.source != null) {
                this.addSource(from.source, from.sourceUses, true);
                from.source = null;
                from.sourceUses = 0;
            }
            for (int i = 0; i < from.extraUsed; i++) {
                if (from.extraSources[i] != null) {
                    this.addSource(from.extraSources[i], from.extraUses[i], true);
                    from.extraSources[i] = null;
                    from.extraUses[i] = 0;
                }
            }
            from.extraUsed = 0;
        }

        void ensureSourceCapacity(int required) {
            int capacity = this.extraSources == null ? 0 : this.extraSources.length;
            if (required <= capacity) {
                return;
            }
            int next = Math.max(required, Math.max(2, capacity * 2));
            ByteBuf[] sources = this.extraSources == null ? new ByteBuf[next] : Arrays.copyOf(this.extraSources, next);
            int[] uses = this.extraUses == null ? new int[next] : Arrays.copyOf(this.extraUses, next);
            this.extraSources = sources;
            this.extraUses = uses;
            this.updateRetainedWeight();
        }

        void ensureCapacity(int required) {
            int capacity = this.data == null ? 0 : this.data.length / 3;
            if (required <= capacity) {
                return;
            }
            int next = Math.max(required, Math.min(Integer.MAX_VALUE / 4, Math.max(4, capacity * 2)));
            Object[] data = this.data == null ? new Object[next * 3] : Arrays.copyOf(this.data, next * 3);
            int[] ranges = this.ranges == null ? new int[next * 4] : Arrays.copyOf(this.ranges, next * 4);
            this.data = data;
            this.ranges = ranges;
            this.updateRetainedWeight();
        }
    }
}
