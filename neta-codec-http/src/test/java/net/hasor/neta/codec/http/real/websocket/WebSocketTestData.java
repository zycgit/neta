/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
