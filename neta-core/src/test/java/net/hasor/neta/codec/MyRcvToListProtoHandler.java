/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
import java.util.List;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

public class MyRcvToListProtoHandler implements ProtoHandler<String, String> {

    private final List<String> rcvMessage;

    public MyRcvToListProtoHandler(List<String> rcvMessage) {
        this.rcvMessage = rcvMessage;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
        while (src.hasMore()) {
            this.rcvMessage.add(src.takeMessage());
        }
        return ProtoStatus.Next;
    }
}
