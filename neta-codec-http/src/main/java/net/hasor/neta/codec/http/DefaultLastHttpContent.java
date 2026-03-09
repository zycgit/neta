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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link LastHttpContent}.
 * <p>
 * This is the terminal content object of a message body. It may carry a final payload buffer but
 * does not carry trailer headers in this object model.
 */
public class DefaultLastHttpContent extends DefaultHttpContent implements LastHttpContent {
    public static final LastHttpContent EMPTY = new DefaultLastHttpContent(ByteBuf.EMPTY);

    /**
     * Creates a body chunk with the specified payload.
     * @param content the chunk payload
     */
    public DefaultLastHttpContent(ByteBuf content) {
        super(content);
    }
}
