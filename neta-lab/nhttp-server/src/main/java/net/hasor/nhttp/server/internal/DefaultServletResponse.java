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
package net.hasor.nhttp.server.internal;

/**
 * @deprecated Replaced by {@link InternalServletResponse}, which writes through a
 *             {@link net.hasor.nhttp.server.connector.ResponseSink} and supports
 *             both buffered and streaming response modes.
 *             This stub is kept only as a placeholder; do not use it.
 */
@Deprecated
public final class DefaultServletResponse {
    private DefaultServletResponse() {
    }
}
