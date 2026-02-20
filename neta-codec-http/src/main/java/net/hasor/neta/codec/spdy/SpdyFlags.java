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
package net.hasor.neta.codec.spdy;

/**
 * SPDY/3.1 flag constants.
 * <p>
 * Flags occupy 8 bits in the frame header and are specific to frame types.
 */
public final class SpdyFlags {
    /** No flags set. */
    public static final int NONE                = 0x00;
    /** FLAG_FIN (0x01) - indicates the stream is being closed by the sender. */
    public static final int FLAG_FIN            = 0x01;
    /** FLAG_UNIDIRECTIONAL (0x02) - for SYN_STREAM, indicates unidirectional stream. */
    public static final int FLAG_UNIDIRECTIONAL = 0x02;

    private SpdyFlags() {
    }

    /** Returns true if FLAG_FIN is set. */
    public static boolean fin(int flags) {
        return (flags & FLAG_FIN) != 0;
    }

    /** Returns true if FLAG_UNIDIRECTIONAL is set. */
    public static boolean unidirectional(int flags) {
        return (flags & FLAG_UNIDIRECTIONAL) != 0;
    }
}
