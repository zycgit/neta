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
