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
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpStatus;

/**
 * Callback used to complete or reject a server-side handshake decision.
 * <p>
 * Supports plain accept as well as reject responses with custom status, headers, and body.
 */
public interface WebSocketHandshakeCallback {
    void accept();

    void accept(HttpHeaders headers);

    void reject();

    void reject(HttpStatus status);

    void reject(HttpStatus status, byte[] body);

    void reject(HttpStatus status, HttpHeaders headers, byte[] body);

    void reject(int code, String reasonPhrase);

    void reject(int code, String reasonPhrase, byte[] body);

    void reject(int code, String reasonPhrase, HttpHeaders headers, byte[] body);
}