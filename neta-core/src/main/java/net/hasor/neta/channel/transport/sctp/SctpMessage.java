/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.transport.sctp;
import com.sun.nio.sctp.MessageInfo;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Represents one SCTP message, including both {@link MessageInfo} and the message body.
 * <p>This type passes SCTP-specific metadata and the actual payload through the protocol pipeline.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SctpMessage {
    private final MessageInfo info;
    private final ByteBuf     byteBuf;

    private SctpMessage(MessageInfo info, ByteBuf byteBuf) {
        this.info = info;
        this.byteBuf = byteBuf;
    }

    /**
     * Create an SCTP message object.
     * @param info the SCTP message metadata
     * @param byteBuf the message body buffer
     * @return the newly created message object
     */
    public static SctpMessage of(MessageInfo info, ByteBuf byteBuf) {
        return new SctpMessage(info, byteBuf);
    }

    /**
     * Return the SCTP message metadata.
     * @return the message metadata
     */
    public MessageInfo getInfo() {
        return this.info;
    }

    /**
     * Return the message body buffer.
     * @return the message body data
     */
    public ByteBuf getByteBuf() {
        return this.byteBuf;
    }
}