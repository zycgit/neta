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
 * Wraps raw bytes that travel through the HTTP pipeline without request/response parsing.
 * <p>
 * This type is primarily used by the HTTP transparent mode: decoders wrap inbound {@link ByteBuf}
 * instances as {@link HttpByteBuf}, and encoders pass their {@link #content()} through directly
 * without applying HTTP framing.
 * <p>
 * Callers that need an extra reference to the payload should retain the returned {@link ByteBuf}
 * directly via {@link #content()}.
 */
public interface HttpByteBuf extends HttpObject {
    /** Returns the payload. */
    ByteBuf content();
}
