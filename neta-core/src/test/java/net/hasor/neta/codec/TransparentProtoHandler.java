/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Transparent conveyor belt.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class TransparentProtoHandler<T> implements ProtoHandler<T, T> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<T> src, ProtoSndQueue<T> dst) {
        dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));
        return ProtoStatus.Next;
    }
}
