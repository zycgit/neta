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