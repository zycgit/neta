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
package net.hasor.neta.codec.http.routing;

/**
 * Standard branch key definitions used by the HTTP routing system.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface HttpRouteKey {
    /** HTTP/1.1 branch key reused by cleartext entries, TLS ALPN entries, and TLS aggregate entries. */
    String BRANCH_H1     = "http/1.1";
    /** HTTP/2 branch key reused by cleartext entries, TLS ALPN entries, and TLS aggregate entries. */
    String BRANCH_H2     = "h2";
    /** h2c upgrade branch key reused by cleartext entries and TLS aggregate entries. */
    String BRANCH_H2C    = "h2c-upgrade";
    /** HTTP/3 branch key, which usually corresponds to the ALPN identifier {@code h3} in QUIC handshakes. */
    String BRANCH_H3     = "h3";
    /** Traffic branch key used after an HTTP upgrade to WebSocket completes. */
    String BRANCH_SOCKET = "http-socket";
}
