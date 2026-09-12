/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
