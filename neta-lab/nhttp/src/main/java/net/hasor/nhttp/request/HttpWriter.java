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
package net.hasor.nhttp.request;
import java.io.IOException;

import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpVersion;

/**
 * Converts a high-level {@link Request} into outbound HTTP objects.
 * <p>
 * Implementations are responsible for applying HTTP-version-specific framing semantics such as the
 * request-target shape, {@code Host}, {@code Content-Length}, and HTTP/1.1 chunked transfer coding.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface HttpWriter {
    /**
     * Serializes the request for the target HTTP version.
     * <p>
     * For HTTP/1.x writers this is the step where RFC-facing wire decisions become concrete,
     * including whether the body is framed by {@code Content-Length} or
     * {@code Transfer-Encoding: chunked}.
     */
    HttpObject[] write(Request request, HttpVersion version) throws IOException;
}