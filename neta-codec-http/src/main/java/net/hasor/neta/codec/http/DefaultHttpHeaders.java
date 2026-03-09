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
package net.hasor.neta.codec.http;
import java.util.*;

/**
 * Default implementation of {@link HttpHeaders} backed by lazily materialized header entries.
 */
public class DefaultHttpHeaders extends HttpHeaderNames implements HttpHeaders {
    private final List<DefaultHttpHeaderEntry> entries;
    private       int                          streamId;

    public DefaultHttpHeaders() {
        this.entries = new ArrayList<>();
    }

    private static boolean equalsIgnoreCase(CharSequence left, CharSequence right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < left.length(); i++) {
            char c1 = left.charAt(i);
            char c2 = right.charAt(i);
            if (c1 != c2 && toLowerAscii(c1) != toLowerAscii(c2)) {
                return false;
            }
        }
        return true;
    }

    private static char toLowerAscii(char ch) {
        return (ch >= 'A' && ch <= 'Z') ? (char) (ch + 32) : ch;
    }

    @Override
    public int streamId() {
        return this.streamId;
    }

    // write

    @Override
    public HttpHeaders streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public void release() {
        for (DefaultHttpHeaderEntry entry : this.entries) {
            entry.release();
        }
        this.entries.clear();
    }

    public DefaultHttpHeaders addHeader(String name, String value) {
        return this.addHeader((CharSequence) name, value);
    }

    public DefaultHttpHeaders addHeader(CharSequence name, CharSequence value) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        this.entries.add(new DefaultHttpHeaderEntry(name, value));
        return this;
    }

    public DefaultHttpHeaders addHeaderEntry(DefaultHttpHeaderEntry header) {
        if (header == null) {
            return this;
        }

        this.entries.add(header.retain());
        return this;
    }

    public DefaultHttpHeaders appendHeaders(HttpHeaders headers) {
        if (headers == null || headers.headerSize() == 0) {
            return this;
        }

        if (headers instanceof DefaultHttpHeaders) {
            DefaultHttpHeaders source = (DefaultHttpHeaders) headers;
            List<DefaultHttpHeaderEntry> snapshot = new ArrayList<>(source.entries);
            for (DefaultHttpHeaderEntry entry : snapshot) {
                this.entries.add(entry.retain());
            }
            return this;
        }

        for (String name : headers.headerNames()) {
            for (String value : headers.getValues(name)) {
                this.entries.add(new DefaultHttpHeaderEntry(name, value));
            }
        }
        return this;
    }

    public DefaultHttpHeaders setHeader(String name, String value) {
        return this.setHeader((CharSequence) name, value);
    }

    public DefaultHttpHeaders setHeader(CharSequence name, CharSequence value) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        this.removeHeader(name.toString());
        return this.addHeader(name, value);
    }

    // read

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
            if (equalsIgnoreCase(entry.getName(), name)) {
                entry.release();
                this.entries.remove(i);
            }
        }
        return this;
    }

    @Override
    public List<String> getValues(String name) {
        if (name == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (DefaultHttpHeaderEntry entry : this.entries) {
            if (equalsIgnoreCase(entry.getName(), name)) {
                result.add(entry.getValue().toString());
            }
        }

        return result.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    @Override
    public String getString(String name) {
        CharSequence value = this.findFirst(name);
        return value == null ? null : value.toString();
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

    //

    @Override
    public Set<String> headerNames() {
        if (this.entries.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> result = new LinkedHashSet<>(this.entries.size());
        for (DefaultHttpHeaderEntry entry : this.entries) {
            result.add(entry.getName().toString());
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public int headerSize() {
        return this.entries.size();
    }

    private CharSequence findFirst(String name) {
        if (name == null) {
            return null;
        }

        for (DefaultHttpHeaderEntry entry : this.entries) {
            if (equalsIgnoreCase(entry.getName(), name)) {
                return entry.getValue();
            }
        }

        return null;
    }
}