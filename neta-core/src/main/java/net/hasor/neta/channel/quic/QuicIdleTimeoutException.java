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
package net.hasor.neta.channel.quic;
import net.hasor.neta.channel.SoTimeoutException;

/**
 * Thrown when a QUIC connection or stream is closed due to idle timeout.
 * <p>
 * <b>Connection-level</b> (RFC 9000 §10.1): if no packets are received within
 * {@code max_idle_timeout} (the minimum of both endpoints' values), the connection
 * is silently closed without sending a CONNECTION_CLOSE frame.
 * <p>
 * <b>Stream-level</b>: an application-defined idle timeout for individual streams.
 * If no data is sent or received on a stream within the configured period, the
 * stream is reset and this exception is propagated through the stream's pipeline.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicSoConfig#setTpMaxIdleTimeout(long)
 * @see QuicSoConfig#setStreamIdleTimeoutMs(long)
 */
public class QuicIdleTimeoutException extends SoTimeoutException {
    private final boolean connectionLevel;

    /**
     * @param message description of the timeout event
     * @param connectionLevel {@code true} if this is a connection-level timeout, {@code false} for stream-level
     */
    public QuicIdleTimeoutException(String message, boolean connectionLevel) {
        super(message);
        this.connectionLevel = connectionLevel;
    }

    /**
     * Returns {@code true} if this timeout occurred at the connection level (RFC 9000 §10.1),
     * or {@code false} if it is a stream-level idle timeout.
     */
    public boolean isConnectionLevel() {
        return this.connectionLevel;
    }
}
