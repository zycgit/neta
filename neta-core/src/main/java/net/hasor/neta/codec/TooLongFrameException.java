/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
/**
 * {@link CodecException} raised when decoded frame data exceeds the configured
 * upper bound.
 * <p>Frame-oriented handlers such as {@link net.hasor.neta.codec.LimitFrameHandler}
 * can throw this exception to stop oversized payloads before they consume too
 * much memory or violate protocol limits.
 * <p>The exception message typically records the observed length and the allowed
 * maximum to make diagnostics easier.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 * @see CodecException
 * @see BadFrameException
 */
public class TooLongFrameException extends CodecException {
    /** Creates a new instance. */
    public TooLongFrameException() {
    }

    /** Creates a new instance. */
    public TooLongFrameException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates a new instance. */
    public TooLongFrameException(String message) {
        super(message);
    }

    /** Creates a new instance. */
    public TooLongFrameException(Throwable cause) {
        super(cause);
    }
}
