/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
/**
 * Specialised {@link LimitFrameHandler} that only emits exact-size frames.
 * <p>
 * Internally this sets {@code minLength == maxLength == fixedLength}, which means
 * bytes are accumulated until a full frame is available and any trailing remainder
 * smaller than {@code fixedLength} stays buffered instead of being emitted.
 * <pre>
 * fixedLength = 10
 * input queue:   [4 bytes] [10 bytes] [2 bytes]
 * output frames: [10 bytes]
 * remainder:     [6 bytes] kept in the source queue
 * </pre>
 * <p><b>Ownership:</b> same as {@link LimitFrameHandler}; emitted fixed-size frames are new
 * buffers owned by downstream, while source buffers stay under queue-managed lifecycle.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class FixedLengthFrameHandler extends LimitFrameHandler {
    /**
     * Creates a new decoder/encoder.
     * @param fixedLength the minimum/maximum length of the decoded frame.
     */
    public FixedLengthFrameHandler(int fixedLength) {
        super(fixedLength, fixedLength);
    }
}
