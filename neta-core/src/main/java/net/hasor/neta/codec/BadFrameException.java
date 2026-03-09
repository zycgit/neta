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
 * {@link CodecException} that marks structurally invalid frame data.
 * <p>
 * Use this exception when the current bytes cannot represent a valid frame
 * under the active protocol rules, for example after decoding an illegal length
 * value, a broken header, or another unrecoverable framing error.
 * <p>
 * The exact recovery strategy is defined by the surrounding pipeline and channel
 * error handling, not by this exception type itself.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 * @see CodecException
 * @see TooLongFrameException
 */
public class BadFrameException extends CodecException {
    /** Creates a new instance. */
    public BadFrameException() {
    }

    /** Creates a new instance. */
    public BadFrameException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates a new instance. */
    public BadFrameException(String message) {
        super(message);
    }

    /** Creates a new instance. */
    public BadFrameException(Throwable cause) {
        super(cause);
    }
}
