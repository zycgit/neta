/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Represents a trailer header block emitted after a chunked message body.
 * <p>
 * Trailer headers are independent from the initial header section. They appear
 * after the last chunk marker and before {@link LastHttpContent}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public interface TrailerHttpHeaders extends HttpHeaders {
}
