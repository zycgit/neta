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
package net.hasor.neta.bytebuf;
/**
 * A {@link StringView} for text that is physically split across multiple {@link ByteBuf} objects.
 * <p>
 * This class is a convenience adapter for callers that already hold a fragmented byte sequence,
 * such as protocol fields that span several receive buffers. It builds a logical composite view
 * over the given buffers and then exposes the requested {@code offset}/{@code length} window as a
 * lazy ASCII {@link CharSequence}.
 * <p>
 * No character data is copied eagerly. Bytes are read from the composite logical address space on
 * demand and the decoded {@link String} is cached after the first full materialization.
 * <p>
 * As with {@link StringView}, this class is only a view. If the underlying buffer content changes
 * before the view is resolved, the observed characters may change as well.
 */
public class StringViewWithComposite extends StringView {
    /**
     * Creates a lazy string view over a visible range in the composite logical address space.
     * @param source fragmented byte buffers that together form one logical sequence
     * @param offset start offset in the composite logical address space
     * @param length visible length in bytes
     */
    public StringViewWithComposite(ByteBuf[] source, int offset, int length) {
        super(ByteBufUtils.compositeBuffer(source), offset, length);
    }
}