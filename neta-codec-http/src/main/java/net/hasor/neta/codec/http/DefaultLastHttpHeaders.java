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