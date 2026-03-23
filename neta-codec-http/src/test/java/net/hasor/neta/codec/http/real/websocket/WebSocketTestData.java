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
package net.hasor.neta.codec.http.real.websocket;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

public final class WebSocketTestData {
    private WebSocketTestData() {
    }

    public static ByteBuf ascii(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(bytes.length, Integer.MAX_VALUE);
        byteBuf.writeBytes(bytes, 0, bytes.length);
        byteBuf.markWriter();
        return byteBuf;
    }
}