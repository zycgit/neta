/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.real.httpserver;

import java.nio.charset.StandardCharsets;

public final class RawHttpRequest {
    public final String method;
    public final String path;
    public final String contentType;
    public final String body;

    public RawHttpRequest(String method, String path, String contentType, byte[] bodyBytes) {
        this.method = method;
        this.path = path;
        this.contentType = contentType;
        this.body = new String(bodyBytes, StandardCharsets.UTF_8);
    }
}
