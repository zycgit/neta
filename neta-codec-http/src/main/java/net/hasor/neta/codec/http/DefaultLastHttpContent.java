/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Default implementation of {@link LastHttpContent}.
 * <p>
 * This is the terminal content object for the message body. It can carry the final
 * payload buffer, but it does not carry trailing headers in the current object model.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultLastHttpContent extends DefaultHttpContent implements LastHttpContent {
    /**
     * Create a terminal content chunk with the specified payload.
     * @param content chunk payload
     */
    public DefaultLastHttpContent(ByteBuf content) {
        super(content);
    }
}
