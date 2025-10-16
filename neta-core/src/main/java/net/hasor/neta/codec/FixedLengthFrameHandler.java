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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * in {@link ByteBuf} is split into multiple or merge {@link ByteBuf} using a fixed length
 * <pre>
 * <b>Case 1</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (26 bytes)    AFTER (20 bytes)
 * +----------+        +----------+----------+
 * | 26 bytes | -----> | 10 bytes | 10 bytes |
 * +----------+        +----------+----------+
 * </pre>
 * <pre>
 * <b>Case 2</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)                     AFTER (10 bytes)
 * +------------------------------+      +------------+
 * | 4 bytes | 10 bytes | 2 bytes | ---> | (10 bytes) |
 * +------------------------------+      +------------+
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