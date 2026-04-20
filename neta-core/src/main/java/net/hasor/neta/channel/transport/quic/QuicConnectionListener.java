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
package net.hasor.neta.channel.transport.quic;
/**
 * Callback interface invoked after a newly accepted server-side QUIC connection has finished initialization.
 * <p>This listener is triggered by {@link QuicAsyncServerChannel} after the handshake completes, the
 * {@link QuicChannel} is created, and its processing pipeline is initialized. It is only used for new
 * connections accepted by the server and does not participate in client-mode connections.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface QuicConnectionListener {
    /**
     * Invoked when a new QUIC connection has been fully established.
     * @param quicChannel the newly created connection-level channel
     */
    void onConnectionEstablished(QuicChannel quicChannel);
}
