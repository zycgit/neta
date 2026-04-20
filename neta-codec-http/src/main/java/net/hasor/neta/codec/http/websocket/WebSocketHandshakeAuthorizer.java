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
package net.hasor.neta.codec.http.websocket;
/**
 * Asynchronous authorization hook that decides whether a server handshake is accepted.
 * <p>
 * Implementations inspect the handshake request snapshot and answer through the
 * supplied callback.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
@FunctionalInterface
public interface WebSocketHandshakeAuthorizer {
    /**
     * Accept or reject the current handshake request.
     * @param request snapshot of the opening handshake request
     * @param callback callback used to accept or reject the handshake
     * @throws Throwable any error raised while authorizing the request
     */
    void authorize(WebSocketHandshakeRequest request, WebSocketHandshakeCallback callback) throws Throwable;
}