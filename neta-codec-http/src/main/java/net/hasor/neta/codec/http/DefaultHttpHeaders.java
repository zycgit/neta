/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.*;

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
            for (int i = 0; i < this.entries.size(); i++) {
                DefaultHttpHeaderEntry entry = this.entries.get(i);
                if (entry != null) {
                    entry.release();
                }
            }
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

        this.entries.addAll(source.entries);
        this.releasableEntries = true;
        if (source.isBad()) {
            this.setBadState(source.badReason());
        }

        source.entries.clear();
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
            DefaultHttpHeaderEntry entry = this.entries.get(i);
            if (!entry.matchesName(name)) {
                continue;
            }

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
            DefaultHttpHeaderEntry entry = this.entries.get(i);
            if (entry.matchesName(name)) {
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
        for (DefaultHttpHeaderEntry entry : this.entries) {
            if (entry.matchesName(name)) {
                if (first == null) {
                    first = entry.getValue();
                } else {
                    if (result == null) {
                        result = new ArrayList<>(4);
                        result.add(first);
                    }
                    result.add(entry.getValue());
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
        DefaultHttpHeaderEntry entry = this.findFirstEntry(name);
        return entry != null ? entry.getValue() : null;
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
        if (name != null) {
            for (DefaultHttpHeaderEntry entry : this.entries) {
                if (entry.matchesName(name)) {
                    try {
                        return entry.parseLongValue();
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                }
            }
        }
        return defaultValue;
    }

    @Override
    public boolean containsHeader(String name) {
        if (name != null) {
            for (DefaultHttpHeaderEntry entry : this.entries) {
                if (entry.matchesName(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Set<String> headerNames() {
        if (this.entries.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> result = new LinkedHashSet<>(this.entries.size());
        for (DefaultHttpHeaderEntry entry : this.entries) {
            result.add(entry.getName());
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public int headerSize() {
        return this.entries.size();
    }

    DefaultHttpHeaderEntry findFirstEntry(String name) {
        if (name != null) {
            for (int i = 0; i < this.entries.size(); i++) {
                DefaultHttpHeaderEntry entry = this.entries.get(i);
                if (entry.matchesName(name)) {
                    return entry;
                }
            }
        }
        return null;
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
