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
import net.hasor.neta.channel.SoException;
/**
 * Base class for QUIC protocol exceptions, carrying a QUIC error code.
 * <p>Corresponds to RFC 9000 Section 20 and serves as a unified exception entry point for connection-level or stream-level protocol errors.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicException extends SoException {
    private final long errorCode;

    /**
     * Creates an exception from an error code and message.
     * @param errorCode QUIC error code
     * @param message exception message
     */
    public QuicException(long errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates an exception from an error code, message, and root cause.
     * @param errorCode QUIC error code
     * @param message exception message
     * @param cause root cause exception
     */
    public QuicException(long errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the associated QUIC error code.
     */
    public long getErrorCode() {
        return this.errorCode;
    }
}
