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
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Compatibility encoder that keeps the classic {@code HttpObject -> Http2Frame}
 * API while internally using the explicit message layer.
 * <p>
 * Internal pipeline:
 * <pre>
 *   HttpObject -> Http2Message -> Http2Frame
 * </pre>
 */
public class Http2HttpToFrameEncoder implements ProtoHandler<HttpObject, Http2Frame> {
    private final Http2HttpToMessageEncoder  httpToMessageEncoder;
    private final Http2MessageToFrameEncoder messageToFrameEncoder;
    private final Http2MessageBridgeQueue    bridgeQueue = new Http2MessageBridgeQueue();

    /**
     * Creates a new HttpObject-to-Http2Frame encoder with default HPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http2HttpToFrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * Creates a new HttpObject-to-Http2Frame encoder with custom HPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     */
    public Http2HttpToFrameEncoder(boolean serverMode, int maxHeaderTableSize) {
        this.httpToMessageEncoder = new Http2HttpToMessageEncoder(serverMode, maxHeaderTableSize);
        this.messageToFrameEncoder = new Http2MessageToFrameEncoder(serverMode, maxHeaderTableSize);
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        this.httpToMessageEncoder.onInit(name, poolSize, context);
        this.messageToFrameEncoder.onInit(name, poolSize, context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        this.bridgeQueue.clear();
        this.httpToMessageEncoder.onMessage(context, src, this.bridgeQueue);
        this.messageToFrameEncoder.onMessage(context, this.bridgeQueue, dst);
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.httpToMessageEncoder.onClose(context);
        this.messageToFrameEncoder.onClose(context);
    }
}
