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
import net.hasor.cobble.StringUtils;
/**
 * Default implementation of a single HTTP header entry.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public class DefaultHttpHeaderEntry {
    private final String name;
    private final String value;

    /**
     * Create a header entry.
     * @param name header name
     * @param value header value
     */
    public DefaultHttpHeaderEntry(String name, String value) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        this.name = name;
        this.value = value;
    }

    /**
     * Return the header name.
     * @return header name
     */
    public String getName() {
        return this.name;
    }

    /**
     * Return the header value.
     * @return header value
     */
    public String getValue() {
        return this.value;
    }
}