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
import net.hasor.cobble.function.Release;

/**
 * Default implementation of {@link HttpHeaders} backed by lazily materialized header entries.
 */
public class DefaultHttpHeaderEntry implements Release {
    private CharSequence name;
    private CharSequence value;
    private int          refCount;

    public DefaultHttpHeaderEntry(CharSequence name, CharSequence value) {
        this.name = name;
        this.value = value;
        this.refCount = 1;
    }

    private static void releaseSequence(CharSequence value) {
        if (value instanceof Release) {
            ((Release) value).release();
        }
    }

    public CharSequence getName() {
        return this.name;
    }

    public CharSequence getValue() {
        return this.value;
    }

    public DefaultHttpHeaderEntry retain() {
        if (this.refCount <= 0) {
            throw new IllegalStateException("HeaderEntry has been released");
        }
        this.refCount++;
        return this;
    }

    @Override
    public void release() {
        if (this.refCount <= 0) {
            return;
        }

        this.refCount--;
        if (this.refCount > 0) {
            return;
        }

        releaseSequence(this.name);
        releaseSequence(this.value);
        this.name = null;
        this.value = null;
    }
}