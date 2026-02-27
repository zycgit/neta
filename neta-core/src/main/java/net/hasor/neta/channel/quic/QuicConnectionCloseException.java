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

/**
 * Thrown when a QUIC {@code CONNECTION_CLOSE} frame is received from the peer
 * or sent locally due to a connection-level error (RFC 9000 §19.19).
 * <p>
 * This exception is propagated through the pipeline on all open stream and
 * datagram channels so that application handlers are notified of the connection teardown.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicErrorCode
 */
public class QuicConnectionCloseException extends QuicException {
    private final String reason;

    /**
     * @param errorCode the QUIC error code from the CONNECTION_CLOSE frame
     * @param reason the reason phrase from the CONNECTION_CLOSE frame (may be empty)
     */
    public QuicConnectionCloseException(long errorCode, String reason) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason);
        this.reason = reason != null ? reason : "";
    }

    /**
     * @param errorCode the QUIC error code from the CONNECTION_CLOSE frame
     * @param reason the reason phrase from the CONNECTION_CLOSE frame (may be empty)
     * @param cause the underlying cause
     */
    public QuicConnectionCloseException(long errorCode, String reason, Throwable cause) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason, cause);
        this.reason = reason != null ? reason : "";
    }

    /** Returns the reason phrase from the CONNECTION_CLOSE frame. */
    public String getReason() {
        return this.reason;
    }
}
