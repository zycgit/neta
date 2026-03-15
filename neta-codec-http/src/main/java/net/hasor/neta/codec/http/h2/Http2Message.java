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
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Semantic HTTP/2 message emitted after one or more {@link Http2Frame} objects
 * have been parsed and, when necessary, reassembled.
 * <p>
 * The HTTP/2 pipeline is intentionally layered as:
 * <pre>
 *   ByteBuf -> Http2Frame -> Http2Message -> HttpObject
 * </pre>
 * {@link Http2Frame} stays close to the wire format, while {@link Http2Message}
 * represents protocol-level units such as header blocks, data chunks, and
 * control messages.
 */
public interface Http2Message extends HttpObject {
    /** Returns the semantic message kind. */
    Type messageType();

    enum Type {
        HEADERS,
        DATA,
        SETTINGS,
        PING,
        WINDOW_UPDATE,
        RST_STREAM,
        GOAWAY
    }
}