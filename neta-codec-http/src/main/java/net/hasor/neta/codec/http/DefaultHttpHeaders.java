/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link HttpHeaders} backed by plain string header entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public class DefaultHttpHeaders extends AbstractHttpObject<HttpHeaders> implements HttpHeaders {
    private final HeaderEntryStore entries;
    private       boolean          releasableEntries;

    /**
     * Create an empty header block.
     */
    public DefaultHttpHeaders() {
        this(8);
    }

    DefaultHttpHeaders(int initialCapacity) {
        this(new HeaderEntryStore(Math.max(0, initialCapacity)), false);
    }

    DefaultHttpHeaders(List<DefaultHttpHeaderEntry> entries, boolean releasableEntries) {
        this(entries instanceof HeaderEntryStore ? (HeaderEntryStore) entries : new HeaderEntryStore(entries), releasableEntries);
    }

    DefaultHttpHeaders(HeaderEntryStore entries, boolean releasableEntries) {
        this.entries = entries != null ? entries : new HeaderEntryStore();
        this.releasableEntries = releasableEntries || !this.entries.isEmpty();
    }

    @Override
    protected HttpHeaders self() {
        return this;
    }

    /**
     * Release header entries and associated state.
     */
    @Override
    public void release() {
        if (this.releasableEntries) {
            this.entries.releaseEntries();
        }
        this.entries.clear();
        this.releasableEntries = false;
        this.resetHttpObjectState();
    }

    // write

    public DefaultHttpHeaders addHeader(String name, String value) {
        return this.addHeader(name, (CharSequence) value);
    }

    public DefaultHttpHeaders addHeader(CharSequence name, CharSequence value) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        DefaultHttpHeaderEntry entry = DefaultHttpHeaderEntry.newEntry(name, value);
        this.entries.add(entry);
        this.releasableEntries = true;
        return this;
    }

    public void addHeaderEntry(DefaultHttpHeaderEntry header) {
        if (header != null) {
            this.entries.add(header);
            this.releasableEntries = true;
        }
    }

    // Borrow the scanner view; the store groups ownership for all rows using it.
    void addDecodedHeader(ByteBuf source, int nameOffset, int nameLength, int valueOffset, int valueLength) {
        this.entries.addDecoded(source, nameOffset, nameLength, valueOffset, valueLength);
        this.releasableEntries = true;
    }

    DefaultHttpHeaders appendOrTransferHeaders(HttpHeaders headers) {
        if (headers == null || headers.headerSize() == 0) {
            return this;
        }
        if (headers instanceof DefaultHttpHeaders && headers != this) {
            return this.transferHeaders((DefaultHttpHeaders) headers);
        }
        return this.appendHeaders(headers);
    }

    DefaultHttpHeaders transferHeaders(DefaultHttpHeaders source) {
        if (source == null || source.entries.isEmpty()) {
            return this;
        }
        if (source == this) {
            return this.appendHeaders(source);
        }

        this.entries.transferFrom(source.entries);
        this.releasableEntries = true;
        if (source.isBad()) {
            this.setBadState(source.badReason());
        }

        source.releasableEntries = false;
        return this;
    }

    public DefaultHttpHeaders appendHeaders(HttpHeaders headers) {
        if (headers == null || headers.headerSize() == 0) {
            return this;
        }

        DefaultHttpHeaders source = defaultHeaderBlock(headers);
        if (source != null) {
            if (source == this) {
                List<DefaultHttpHeaderEntry> copies = new ArrayList<>(source.entries.size());
                for (DefaultHttpHeaderEntry entry : source.entries) {
                    copies.add(entry.materializeCopy());
                }
                this.ensureEntryCapacity(copies.size());
                this.entries.addAll(copies);
                this.releasableEntries = true;
            } else {
                this.ensureEntryCapacity(source.entries.size());
                for (DefaultHttpHeaderEntry entry : source.entries) {
                    this.entries.add(entry.materializeCopy());
                }
                if (!source.entries.isEmpty()) {
                    this.releasableEntries = true;
                }
            }
        } else {
            for (String name : headers.headerNames()) {
                for (String value : headers.getValues(name)) {
                    this.entries.add(DefaultHttpHeaderEntry.newEntry(name, value));
                }
            }
            if (headers.headerSize() > 0) {
                this.releasableEntries = true;
            }
        }

        return this;
    }

    public DefaultHttpHeaders setHeader(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        DefaultHttpHeaderEntry replacement;
        boolean replaced = false;
        for (int i = this.entries.size() - 1; i >= 0; i--) {
            if (!this.entries.matchesName(i, name)) {
                continue;
            }
            DefaultHttpHeaderEntry entry = this.entries.get(i);

            if (!replaced) {
                replacement = DefaultHttpHeaderEntry.newEntry(name, value);
                this.entries.set(i, replacement);
                this.releasableEntries = true;
                replaced = true;
            } else {
                this.entries.remove(i);
            }

            if (this.releasableEntries) {
                entry.release();
            }
        }
        if (!replaced) {
            this.addHeader(name, value);
        }
        return this;
    }

    public DefaultHttpHeaders clearHeader() {
        this.release();
        return this;
    }

    public DefaultHttpHeaders removeHeader(String name) {
        if (name == null) {
            return this;
        }

        for (int i = this.entries.size() - 1; i >= 0; i--) {
            if (this.entries.matchesName(i, name)) {
                DefaultHttpHeaderEntry entry = this.entries.get(i);
                if (this.releasableEntries) {
                    entry.release();
                }
                this.entries.remove(i);
            }
        }
        if (this.entries.isEmpty()) {
            this.releasableEntries = false;
        }
        return this;
    }

    // read

    @Override
    public List<String> getValues(String name) {
        if (name == null) {
            return Collections.emptyList();
        }

        String first = null;
        List<String> result = null;
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.entries.matchesName(i, name)) {
                if (first == null) {
                    first = this.entries.getValue(i);
                } else {
                    if (result == null) {
                        result = new ArrayList<>(4);
                        result.add(first);
                    }
                    result.add(this.entries.getValue(i));
                }
            }
        }

        if (result != null) {
            return Collections.unmodifiableList(result);
        }
        return first == null ? Collections.emptyList() : Collections.singletonList(first);
    }

    @Override
    public String getString(String name) {
        return this.entries.findFirstValue(name);
    }

    @Override
    public int getInt(String name, int defaultValue) {
        String value = this.getString(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public long getLong(String name, long defaultValue) {
        int index = this.entries.findFirstIndex(name);
        if (index >= 0) {
            try {
                return this.entries.parseLongValue(index);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public boolean containsHeader(String name) {
        return this.entries.findFirstIndex(name) >= 0;
    }

    @Override
    public Set<String> headerNames() {
        if (this.entries.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> result = new LinkedHashSet<>(this.entries.size());
        for (int i = 0; i < this.entries.size(); i++) {
            result.add(this.entries.getName(i));
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public int headerSize() {
        return this.entries.size();
    }

    DefaultHttpHeaderEntry findFirstEntry(String name) {
        int index = this.entries.findFirstIndex(name);
        return index >= 0 ? this.entries.get(index) : null;
    }

    List<DefaultHttpHeaderEntry> headerEntries() {
        return this.entries;
    }

    private static DefaultHttpHeaders defaultHeaderBlock(HttpHeaders headers) {
        if (headers instanceof DefaultHttpHeaders) {
            return (DefaultHttpHeaders) headers;
        }
        if (headers instanceof DefaultFullHttpRequest) {
            return ((DefaultFullHttpRequest) headers).headerBlock();
        }
        if (headers instanceof DefaultFullHttpResponse) {
            return ((DefaultFullHttpResponse) headers).headerBlock();
        }
        return null;
    }

    private void ensureEntryCapacity(int additional) {
        if (additional <= 0) {
            return;
        }
        this.entries.ensureAppendCapacity(additional);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(streamId=" + this.streamId() + ", headers=" + this.entries.size() + ')';
    }
}
