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
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Splits an inbound {@link ByteBuf} byte stream into frames based on one or more
 * fixed byte-sequence delimiters.
 * <p>
 * Unlike {@link LineBasedFrameHandler} which only recognises {@code '\n'}/{@code '\r\n'},
 * this handler accepts arbitrary byte sequences as delimiters — for example
 * {@code 0x00}, {@code 0xFF 0xFE}, or the HTTP header separator {@code "\r\n\r\n"}.
 * <p>
 * An exception is thrown when the frame length exceeds the configured
 * {@code maxLength} before a delimiter is found.
 * <p>
 * <b>Typical use cases:</b>
 * <ul>
 *   <li>Custom binary protocols with fixed end-of-frame markers</li>
 *   <li>Text protocols with non-newline terminators (e.g. null-terminated strings)</li>
 *   <li>HTTP/1.x header block detection ({@code \r\n\r\n})</li>
 * </ul>
 * <p>
 * <b>Note:</b> this implementation is a placeholder; the actual delimiter-scanning
 * logic is not yet implemented and currently throws an exception.
 * <p><b>Ownership:</b> once implemented, emitted delimiter frames are expected to be new
 * buffers owned by downstream, while source buffers remain under queue-managed lifecycle.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 * @see LineBasedFrameHandler
 * @see LengthFieldBasedFrameHandler
 */
public class DelimiterBasedFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws IOException {
        throw new UnsupportedEncodingException();
    }
}