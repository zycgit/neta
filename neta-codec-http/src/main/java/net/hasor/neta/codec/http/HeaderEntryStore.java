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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Compact, growable storage for HTTP header entries.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-22
 */
final class HeaderEntryStore extends ArrayList<DefaultHttpHeaderEntry> {
    HeaderEntryStore() {
        this(0);
    }

    HeaderEntryStore(int initialCapacity) {
        super(initialCapacity);
    }

    HeaderEntryStore(List<DefaultHttpHeaderEntry> entries) {
        this(entries != null ? entries.size() : 0);
        if (entries != null && !entries.isEmpty()) {
            this.addAll(entries);
        }
    }

    void ensureAppendCapacity(int additional) {
        if (additional > 0) {
            this.ensureCapacity(this.size() + additional);
        }
    }

    @Override
    public boolean addAll(Collection<? extends DefaultHttpHeaderEntry> entries) {
        if (entries instanceof HeaderEntryStore source && entries != this) {
            int incoming = source.size();
            this.ensureAppendCapacity(incoming);
            for (int i = 0; i < incoming; i++) {
                this.add(source.get(i));
            }
            return incoming != 0;
        }
        return super.addAll(entries);
    }
}
