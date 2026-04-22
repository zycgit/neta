/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
    static final int                                                  RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static final RecycleObjectPool.ObjHandler<DefaultHttpHeaderEntry> RECYCLE_HANDLER = //
            new RecycleObjectPool.ObjHandler<DefaultHttpHeaderEntry>() {
                @Override
                public DefaultHttpHeaderEntry create() {
                    return new DefaultHttpHeaderEntry();
                }

                @Override
                public void free(DefaultHttpHeaderEntry target) {
                    RecycleObjectPool.free(RECYCLE_INDEX, target);
                }
            };
    private CharSequence                                              name;
    private CharSequence                                              value;
    private ByteBuf                                                   source;
    private int                                                       nameOffset;
    private int                                                       nameLength;
    private int                                                       valueOffset;
    private int                                                       valueLength;

    private DefaultHttpHeaderEntry() {
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
        DefaultHttpHeaderEntry entry = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        entry.initEntry(name, value);
        return entry;
    }

    static DefaultHttpHeaderEntry newOwnedEntry(CharSequence name, ByteBuf source, int valueOffset, int valueLength) {
        DefaultHttpHeaderEntry entry = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        entry.initEntry(name, source, valueOffset, valueLength, false);
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

        this.resetRefCnt();
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

        this.resetRefCnt();
        this.name = name;
        this.value = valueLength == 0 ? "" : null;
        this.source = valueLength == 0 ? null : (retainSource ? source.retain() : source);
        this.nameOffset = 0;
        this.nameLength = 0;
        this.valueOffset = valueOffset;
        this.valueLength = valueLength;
    }

    /**
     * Return the header name.
     * @return header name
     */
    public String getName() {
        String resolved = this.resolveName();
        this.name = resolved;
        this.releaseSourceIfResolved();
        return resolved;
    }

    /**
     * Return the header value.
     * @return header value
     */
    public String getValue() {
        String resolved = this.resolveValue();
        this.value = resolved;
        this.releaseSourceIfResolved();
        return resolved;
    }

    boolean matchesName(CharSequence headerName) {
        if (this.name == null && this.source != null) {
            return HttpCharSequences.equalsIgnoreCase(this.source, this.nameOffset, this.nameLength, headerName);
        }
        return HttpCharSequences.equalsIgnoreCase(this.name, headerName);
    }

    CharSequence valueText() {
        if (this.value == null && this.source != null) {
            String resolved = this.resolveValue();
            this.value = resolved;
            this.releaseSourceIfResolved();
            return resolved;
        }
        return this.value;
    }

    long parseLongValue(long defaultValue) {
        try {
            if (this.value != null) {
                return HttpCharSequences.parseLong(this.value);
            }
            if (this.source != null) {
                return HttpCharSequences.parseLong(this.source, this.valueOffset, this.valueLength);
            }
        } catch (NumberFormatException e) {
            return defaultValue;
        }
        return defaultValue;
    }

    DefaultHttpHeaderEntry materializeCopy() {
        return newEntry(this.getName(), this.getValue());
    }

    @Override
    protected void deallocate() {
        HttpCharSequences.release(this.name);
        HttpCharSequences.release(this.value);
        if (this.source != null && !this.source.isFree()) {
            this.source.release();
        }
        this.name = null;
        this.value = null;
        this.source = null;
        this.nameOffset = 0;
        this.nameLength = 0;
        this.valueOffset = 0;
        this.valueLength = 0;
        RECYCLE_HANDLER.free(this);
    }

    private String resolveName() {
        if (this.name != null) {
            return HttpCharSequences.materialize(this.name);
        }
        return this.readSourceSlice(this.nameOffset, this.nameLength);
    }

    private String resolveValue() {
        if (this.value != null) {
            return HttpCharSequences.materialize(this.value);
        }
        return this.readSourceSlice(this.valueOffset, this.valueLength);
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
