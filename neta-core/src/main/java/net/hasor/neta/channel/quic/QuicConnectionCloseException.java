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
 * Exception thrown when a QUIC CONNECTION_CLOSE frame is received or sent.
 * <p>Corresponds to RFC 9000 Section 19.19 and represents connection-level shutdown together with its error reason.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicConnectionCloseException extends QuicException {
    private final String reason;

    /**
     * Creates a connection-close exception from an error code and reason.
     * @param errorCode QUIC error code
     * @param reason close reason
     */
    public QuicConnectionCloseException(long errorCode, String reason) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason);
        this.reason = reason != null ? reason : "";
    }

    /**
     * Creates a connection-close exception from an error code, reason, and root cause.
     * @param errorCode QUIC error code
     * @param reason close reason
     * @param cause root cause exception
     */
    public QuicConnectionCloseException(long errorCode, String reason, Throwable cause) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason, cause);
        this.reason = reason != null ? reason : "";
    }

    /**
     * Returns the reason text carried in CONNECTION_CLOSE.
     */
    public String getReason() {
        return this.reason;
    }
}
