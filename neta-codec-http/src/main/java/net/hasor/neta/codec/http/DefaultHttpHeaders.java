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
import java.util.*;
/**
 * Default implementation of {@link HttpHeaders} backed by plain string header entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public class DefaultHttpHeaders extends AbstractHttpObject<HttpHeaders> implements HttpHeaders {
    private final HeaderEntryStore entries;
    private boolean                releasableEntries;

    /**
     * Create an empty header block.
     */
    public DefaultHttpHeaders() {
        this(4);
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
            for (DefaultHttpHeaderEntry entry : this.entries) {
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

        this.ensureEntryCapacity(source.entries.size());
        this.entries.addAll(source.entries);
        this.releasableEntries = this.releasableEntries || !source.entries.isEmpty();
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

        if (headers instanceof DefaultHttpHeaders) {
            DefaultHttpHeaders source = (DefaultHttpHeaders) headers;
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

        List<String> result = new ArrayList<>();
        for (DefaultHttpHeaderEntry entry : this.entries) {
            if (entry.matchesName(name)) {
                result.add(entry.getValue());
            }
        }

        return result.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    @Override
    public String getString(String name) {
        return this.findFirst(name);
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
        String value = this.getString(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean containsHeader(String name) {
        return this.findFirst(name) != null;
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

    private String findFirst(String name) {
        if (name == null) {
            return null;
        }

        for (DefaultHttpHeaderEntry entry : this.entries) {
            if (entry.matchesName(name)) {
                return entry.getValue();
            }
        }

        return null;
    }

    List<DefaultHttpHeaderEntry> headerEntries() {
        return this.entries;
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
