/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
