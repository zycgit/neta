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
import net.hasor.neta.channel.SoException;

/**
 * Base exception for QUIC protocol errors, carrying a QUIC error code (RFC 9000 §20).
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicException extends SoException {
    private final long errorCode;

    /** Creates a QUIC exception with an error code and message. */
    public QuicException(long errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Creates a QUIC exception with an error code, message, and cause. */
    public QuicException(long errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Returns the QUIC error code associated with this exception. */
    public long getErrorCode() {
        return this.errorCode;
    }
}
