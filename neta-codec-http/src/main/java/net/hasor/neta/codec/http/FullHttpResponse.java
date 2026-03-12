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
/**
 * Represents a fully aggregated HTTP response.
 * <p>
 * A {@link FullHttpResponse} collapses the staged response flow into one object containing the
 * status line, one final merged header set, and the aggregated content. It is typically
 * produced by an {@link HttpObjectAggregator}.
 * <p>
 * This aggregated form does not preserve a separate trailer-header container. If the staged flow
 * collected additional header fields at the logical end of the message, they are exposed through
 * the same header set as ordinary headers.
 */
public interface FullHttpResponse extends HttpResponse, LastHttpHeaders, LastHttpContent {
}