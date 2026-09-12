/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
