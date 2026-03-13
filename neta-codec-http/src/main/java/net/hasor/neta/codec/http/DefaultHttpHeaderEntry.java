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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.Release;

/** Default implementation of one HTTP header entry. */
public class DefaultHttpHeaderEntry implements Release {
    private final String name;
    private final String value;

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

    public String getName() {
        return this.name;
    }

    public String getValue() {
        return this.value;
    }

    public DefaultHttpHeaderEntry retain() {
        return this;
    }

    @Override
    public void release() {
        // HTTP header entries only hold immutable strings, so release is intentionally a no-op.
    }
}