/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Represents a fully aggregated HTTP request.
 * <p>
 * A {@link FullHttpRequest} collapses the staged request flow into a single object
 * that exposes the request line, the header view, and the aggregated content.
 * It is typically produced by {@link HttpRequestAggregator} or
 * {@link HttpServerDuplexAggregator}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface FullHttpRequest extends HttpRequest, LastHttpHeaders, LastHttpContent {
}
