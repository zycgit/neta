/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.neta.bytebuf.AbstractReferenceHolder;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of a single HTTP header entry.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public class DefaultHttpHeaderEntry extends AbstractReferenceHolder {
    static final RecycleObjectPool.Recycler<DefaultHttpHeaderEntry> RECYCLER = RecycleObjectPool.recycler(//
            DefaultHttpHeaderEntry::new, DefaultHttpHeaderEntry::resetRefCnt, DefaultHttpHeaderEntry::onRecycle);
    private      CharSequence                                         name;
    private      CharSequence                                         value;
    private      ByteBuf                                              source;
    private      int                                                  nameOffset;
    private      int                                                  nameLength;
    private      int                                                  valueOffset;
    private      int                                                  valueLength;

    private DefaultHttpHeaderEntry() {
    }

    private void resetState() {
        this.name = null;
        this.value = null;
        this.source = null;
        this.nameOffset = 0;
        this.nameLength = 0;
        this.valueOffset = 0;
        this.valueLength = 0;
    }

    private void onRecycle() {
        if (this.name != null && !(this.name instanceof String)) {
            HttpCharSequences.release(this.name);
        }
        if (this.value != null && !(this.value instanceof String)) {
            HttpCharSequences.release(this.value);
        }
        if (this.source != null && !this.source.isFree()) {
            this.source.release();
        }
        this.resetState();
    }

    /**
     * Create a header entry.
     * @param name header name
     * @param value header value
     */
    public DefaultHttpHeaderEntry(String name, String value) {
        this(name, (CharSequence) value);
    }

    public DefaultHttpHeaderEntry(CharSequence name, CharSequence value) {
        this.initEntry(name, value);
    }

    static DefaultHttpHeaderEntry newEntry(String name, String value) {
        return newEntry((CharSequence) name, value);
    }

    static DefaultHttpHeaderEntry newEntry(CharSequence name, CharSequence value) {
        DefaultHttpHeaderEntry entry = RECYCLER.get();
        entry.initEntry(name, value);
        return entry;
    }

    static DefaultHttpHeaderEntry newOwnedEntry(ByteBuf source, int nameOffset, int nameLength, int valueOffset, int valueLength) {
        DefaultHttpHeaderEntry entry = RECYCLER.get();
        entry.initEntry(source, nameOffset, nameLength, valueOffset, valueLength, false);
        return entry;
    }

    @Override
    public DefaultHttpHeaderEntry retain() {
        super.retain();
        return this;
    }

    @Override
    public DefaultHttpHeaderEntry retain(int increment) {
        super.retain(increment);
        return this;
    }

    private void initEntry(CharSequence name, CharSequence value) {
        if (HttpCharSequences.isBlank(name)) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        this.name = name;
        this.value = value;
        this.source = null;
        this.nameOffset = 0;
        this.nameLength = 0;
        this.valueOffset = 0;
        this.valueLength = 0;
    }

    private void initEntry(CharSequence name, ByteBuf source, int valueOffset, int valueLength, boolean retainSource) {
        if (HttpCharSequences.isBlank(name)) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (valueOffset < 0 || valueLength < 0) {
            throw new IllegalArgumentException("value range must not be negative");
        }

        this.name = name;
        this.value = valueLength == 0 ? "" : null;
        this.source = valueLength == 0 ? null : (retainSource ? source.retain() : source);
        this.nameOffset = 0;
        this.nameLength = 0;
        this.valueOffset = valueOffset;
        this.valueLength = valueLength;
    }

    private void initEntry(ByteBuf source, int nameOffset, int nameLength, int valueOffset, int valueLength, boolean retainSource) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (nameOffset < 0 || nameLength <= 0 || valueOffset < 0 || valueLength < 0) {
            throw new IllegalArgumentException("name/value range must not be negative or empty");
        }

        this.name = null;
        this.value = valueLength == 0 ? "" : null;
        this.source = retainSource ? source.retain() : source;
        this.nameOffset = nameOffset;
        this.nameLength = nameLength;
        this.valueOffset = valueOffset;
        this.valueLength = valueLength;
    }

    /**
     * Return the header name.
     * @return header name
     */
    public String getName() {
        CharSequence current = this.name;
        if (current instanceof String) {
            return (String) current;
        }
        String resolved = current != null ? HttpCharSequences.materialize(current) : this.readSourceSlice(this.nameOffset, this.nameLength);
        this.name = resolved;
        if (this.value instanceof String) {
            this.releaseSourceIfResolved();
        }
        return resolved;
    }

    /**
     * Return the header value.
     * @return header value
     */
    public String getValue() {
        CharSequence current = this.value;
        if (current instanceof String) {
            return (String) current;
        }
        String resolved = current != null ? HttpCharSequences.materialize(current) : this.readSourceSlice(this.valueOffset, this.valueLength);
        this.value = resolved;
        if (this.name instanceof String) {
            this.releaseSourceIfResolved();
        }
        return resolved;
    }

    boolean matchesName(CharSequence headerName) {
        if (this.name == null && this.source != null) {
            return HttpCharSequences.equalsIgnoreCase(this.source, this.nameOffset, this.nameLength, headerName);
        }
        return HttpCharSequences.equalsIgnoreCase(this.name, headerName);
    }

    long parseLongValue() {
        if (this.value == null && this.source != null) {
            return HttpCharSequences.parseLong(this.source, this.valueOffset, this.valueLength);
        }
        return HttpCharSequences.parseLong(this.value);
    }

    boolean hasNonBlankValue() {
        if (this.value == null && this.source != null) {
            return !HttpCharSequences.isBlank(this.source, this.valueOffset, this.valueLength);
        }
        return !HttpCharSequences.isBlank(this.value);
    }

    boolean valueEqualsIgnoreCase(CharSequence expected) {
        if (this.value == null && this.source != null) {
            return HttpCharSequences.equalsIgnoreCase(this.source, this.valueOffset, this.valueLength, expected);
        }
        return HttpCharSequences.equalsIgnoreCase(this.value, expected);
    }

    boolean valueContainsIgnoreCase(CharSequence expected) {
        if (this.value == null && this.source != null) {
            return HttpCharSequences.containsIgnoreCase(this.source, this.valueOffset, this.valueLength, expected);
        }
        return HttpCharSequences.containsIgnoreCase(this.value, expected);
    }

    DefaultHttpHeaderEntry materializeCopy() {
        return newEntry(this.getName(), this.getValue());
    }

    @Override
    protected void deallocate() {
        RECYCLER.recycle(this);
    }

    private String readSourceSlice(int offset, int length) {
        if (this.source == null || this.source.isFree()) {
            return "";
        }
        try {
            return this.source.getString(offset, length, StandardCharsets.US_ASCII);
        } catch (IllegalStateException e) {
            return "";
        }
    }

    private void releaseSourceIfResolved() {
        if (this.source == null) {
            return;
        }
        if (this.name instanceof String && this.value instanceof String) {
            this.source.release();
            this.source = null;
            this.nameOffset = 0;
            this.nameLength = 0;
            this.valueOffset = 0;
            this.valueLength = 0;
        }
    }
}
