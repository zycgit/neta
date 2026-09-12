/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.real.httpserver;

public final class RawHttpResponse {
    public final int    statusCode;
    public final String reason;
    public final String contentType;
    public final String body;

    public RawHttpResponse(int statusCode, String reason, String contentType, String body) {
        this.statusCode = statusCode;
        this.reason = reason;
        this.contentType = contentType;
        this.body = body;
    }
}
