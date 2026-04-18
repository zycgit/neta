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
import net.hasor.cobble.StringUtils;

/**
 * Default implementation of {@link HttpHeaders} backed by plain string header entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public class DefaultHttpHeaders extends AbstractHttpObject<HttpHeaders> implements HttpHeaders {
    private final List<DefaultHttpHeaderEntry> entries;

    /**
     * Create an empty header block.
     */
    public DefaultHttpHeaders() {
        this.entries = new ArrayList<>();
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
        this.entries.clear();
        this.resetHttpObjectState();
    }

    // write

    public DefaultHttpHeaders addHeader(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        this.entries.add(new DefaultHttpHeaderEntry(name, value));
        return this;
    }

    public void addHeaderEntry(DefaultHttpHeaderEntry header) {
        if (header != null) {
            this.entries.add(header);
        }
    }

    public DefaultHttpHeaders appendHeaders(HttpHeaders headers) {
        if (headers == null || headers.headerSize() == 0) {
            return this;
        }

        if (headers instanceof DefaultHttpHeaders) {
            DefaultHttpHeaders source = (DefaultHttpHeaders) headers;
            this.entries.addAll(source.entries);
        } else {
            for (String name : headers.headerNames()) {
                for (String value : headers.getValues(name)) {
                    this.entries.add(new DefaultHttpHeaderEntry(name, value));
                }
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

        this.removeHeader(name);
        return this.addHeader(name, value);
    }

    public DefaultHttpHeaders clearHeader() {
        this.release();
        this.entries.clear();
        return this;
    }

    public DefaultHttpHeaders removeHeader(String name) {
        if (name == null) {
            return this;
        }

        for (int i = this.entries.size() - 1; i >= 0; i--) {
            DefaultHttpHeaderEntry entry = this.entries.get(i);
            if (StringUtils.equalsIgnoreCase(entry.getName(), name)) {
                this.entries.remove(i);
            }
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
            if (StringUtils.equalsIgnoreCase(entry.getName(), name)) {
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
            if (StringUtils.equalsIgnoreCase(entry.getName(), name)) {
                return entry.getValue();
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(streamId=" + this.streamId() + ", headers=" + this.entries.size() + ')';
    }
}