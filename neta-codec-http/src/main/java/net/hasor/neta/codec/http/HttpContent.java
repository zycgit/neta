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
 * Represents one chunk of HTTP message body content.
 * <p>
 * {@link HttpContent} objects appear only after {@link LastHttpHeaders} has closed the header
 * section. A message body may contain zero or more ordinary content chunks and is always finished
 * by {@link LastHttpContent}. Callers that need an extra reference to the payload should retain
 * the returned {@link ByteBuf} directly via {@link #content()}.
 * @see LastHttpContent
 */
public interface HttpContent extends HttpObject {
    /** Returns the payload. */
    ByteBuf content();
}
