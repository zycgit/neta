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
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Represents a content chunk within an HTTP message body.
 * <p>
 * {@link HttpContent} appears only after {@link LastHttpHeaders} has closed the initial header section. A message body may contain zero or more regular content chunks.
 * Messages with an explicit end boundary terminate with {@link LastHttpContent}, while responses delimited by connection close end when the connection closes.
 * If a caller needs to retain the payload reference beyond normal ownership, call retain directly on the {@link ByteBuf} returned by {@link #content()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 * @see LastHttpContent
 */
public interface HttpContent extends HttpObject {
    /**
     * Returns the payload carried by this content chunk.
     * @return payload buffer
     */
    ByteBuf content();

    /**
     * Transfers the current payload ownership out of this wrapper.
     * <p>
     * After transfer, this wrapper no longer owns the payload and later {@link #release()}
     * calls must not release the transferred buffer.
     * @return transferred payload, or {@code null} if this object no longer owns one
     */
    ByteBuf transferContent();
}
