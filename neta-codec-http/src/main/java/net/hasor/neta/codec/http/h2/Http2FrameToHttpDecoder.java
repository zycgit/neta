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
 * Compatibility decoder that preserves the classic {@code Http2Frame -> HttpObject}
 * surface while internally running the layered decode chain.
 * <p>
 * Internal pipeline:
 * <pre>
 *   Http2Frame -> Http2Message -> HttpObject
 * </pre>
 * <p>
 * New code that needs explicit HTTP/2 semantic messages should prefer
 * {@link Http2FrameToMessageDecoder} plus {@link Http2MessageToHttpDecoder}.
 */
public class Http2FrameToHttpDecoder implements ProtoHandler<Http2Frame, HttpObject> {
    private final Http2FrameToMessageDecoder frameToMessageDecoder;
    private final Http2MessageToHttpDecoder  messageToHttpDecoder;
    private final Http2MessageBridgeQueue    bridgeQueue = new Http2MessageBridgeQueue();

    /**
     * Creates a new HTTP/2 frame-to-HttpObject decoder with default HPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http2FrameToHttpDecoder(boolean serverMode) {
        this(serverMode, 4096, 8192);
    }

    /**
     * Creates a new HTTP/2 frame-to-HttpObject decoder with custom HPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     * @param maxHeaderListSize maximum total size of all decoded headers
     */
    public Http2FrameToHttpDecoder(boolean serverMode, int maxHeaderTableSize, int maxHeaderListSize) {
        this.frameToMessageDecoder = new Http2FrameToMessageDecoder(serverMode, maxHeaderTableSize, maxHeaderListSize);
        this.messageToHttpDecoder = new Http2MessageToHttpDecoder();
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
        this.frameToMessageDecoder.onInit(name, poolSize, context);
        this.messageToHttpDecoder.onInit(name, poolSize, context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        this.bridgeQueue.clear();
        this.frameToMessageDecoder.onMessage(context, src, this.bridgeQueue);
        this.messageToHttpDecoder.onMessage(context, this.bridgeQueue, dst);
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.frameToMessageDecoder.onClose(context);
        this.messageToHttpDecoder.onClose(context);
    }
}
