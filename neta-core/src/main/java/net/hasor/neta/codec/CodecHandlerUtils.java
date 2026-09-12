/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.data.ProtoSndQueue;
final class CodecHandlerUtils {
    private CodecHandlerUtils() {
    }

    static boolean offerOwnedBuffer(ProtoSndQueue<ByteBuf> dst, ByteBuf buffer) {
        if (buffer == null) {
            return true;
        }

        boolean accepted = false;
        try {
            accepted = dst.offerMessage(buffer);
            return accepted;
        } finally {
            if (!accepted) {
                buffer.release();
            }
        }
    }
}
