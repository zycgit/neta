/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
/**
 * Base runtime exception for codec layers in the Neta protocol pipeline.
 * <p>
 * Encoders, decoders, and frame splitters can throw this type when the current
 * input cannot be processed normally. Because it extends {@link RuntimeException},
 * codec implementations can raise it directly from pipeline callbacks without
 * changing their method signatures.
 * <p>
 * Uncaught {@code CodecException}s follow the same error path as other pipeline
 * failures and are delivered to the channel's inbound or outbound error flow,
 * depending on where they were raised.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 * @see BadFrameException
 * @see TooLongFrameException
 */
public class CodecException extends RuntimeException {
    /** Creates a new instance. */
    public CodecException() {
    }

    /** Creates a new instance. */
    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates a new instance. */
    public CodecException(String message) {
        super(message);
    }

    /** Creates a new instance. */
    public CodecException(Throwable cause) {
        super(cause);
    }
}
