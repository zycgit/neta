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
public interface HttpRouteKey {
    /** Branch key for HTTP/1.1 over TLS (ALPN protocol identifier "http/1.1"). */
    String BRANCH_H1  = "http/1.1";
    /** Branch key for HTTP/2 over TLS (ALPN protocol identifier "h2"). */
    String BRANCH_H2  = "h2";
    /** Branch key for HTTP/1.1 Upgrade to h2c (RFC 7540 Section 3.2). */
    String BRANCH_H2C = "h2c-upgrade";
    /** Branch key for HTTP/3 over QUIC (ALPN protocol identifier "h3"). */
    String BRANCH_H3  = "h3";
}
