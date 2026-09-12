/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.List;
/**
 * Default implementation of {@link LastHttpHeaders}.
 * <p>
 * This marks the end of the initial header section. Subsequent objects enter the
 * content phase, and chunked messages may still emit trailing headers.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class DefaultLastHttpHeaders extends DefaultHttpHeaders implements LastHttpHeaders {
    public static final LastHttpHeaders EMPTY = new DefaultLastHttpHeaders();

    /**
     * Create an empty terminal header block.
     */
    public DefaultLastHttpHeaders() {
        super();
    }

    DefaultLastHttpHeaders(List<DefaultHttpHeaderEntry> entries, boolean releasableEntries) {
        super(entries, releasableEntries);
    }

    /**
     * Create a terminal header block copy from an existing header block.
     * @param headers source header block
     */
    public DefaultLastHttpHeaders(HttpHeaders headers) {
        if (headers != null) {
            this.appendHeaders(headers);
        }
    }
}
