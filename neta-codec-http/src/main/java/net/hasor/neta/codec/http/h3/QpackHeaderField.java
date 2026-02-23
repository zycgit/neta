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
 * Represents a single header field (name-value pair) used in QPACK
 * header compression for HTTP/3.
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackHeaderField {
    private final String name;
    private final String value;

    public QpackHeaderField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String name() {
        return name;
    }

    public String value() {
        return value;
    }

    /**
     * Returns the size of this header field as defined in RFC 9204, Section 3.2.1.
     * Size = name length + value length + 32 (overhead).
     */
    public int size() {
        return name.length() + value.length() + 32;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
