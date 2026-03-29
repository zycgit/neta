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

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Stream-aware HTTP/2 receive-side reactor.
 * <p>
 * This class is now a thin HTTP/2 binding over the generic core {@link ProtoPartitionDuplexer}.
 * It only defines how to extract/bind {@code streamId} and when one inbound stream should be closed.
 * </p>
 */
public class Http2StreamReactorDuplexe implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> delegate;

    @SuppressWarnings("unchecked")
    public Http2StreamReactorDuplexe(Consumer<ProtoBuilder<HttpObject, HttpObject>> streamPipelineFactory) {
        Objects.requireNonNull(streamPipelineFactory, "streamPipelineFactory is null.");
        ProtoInitializer initializer = ctx -> {
            ProtoBuilder<HttpObject, HttpObject> builder = ProtoHelper.typed(HttpObject.class, HttpObject.class);
            streamPipelineFactory.accept(builder);
            builder.build().config(ctx);
        };
        ProtoPartitionDuplexer<HttpObject, HttpObject> duplexer = new ProtoPartitionDuplexer<>(new Http2StreamSelector());
        duplexer.setInitializer(initializer);
        this.delegate = duplexer;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        return this.delegate.onMessage(context, isRcv, rcvUp, rcvDown, sndUp, sndDown);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        return this.delegate.onUserEvent(context, event, isRcv);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.delegate.onClose(context);
    }

    private static final class Http2StreamSelector implements ProtoPartitionSelector<HttpObject> {
        @Override
        public String route(ProtoContext context, boolean isRcv, HttpObject message) {
            return message == null || message.streamId() <= 0 ? null : String.valueOf(message.streamId());
        }

        @Override
        public String route(ProtoContext context, boolean isRcv, SoUserEvent event) {
            int streamId = extractStreamId(event == null ? null : event.getData());
            return streamId <= 0 ? null : String.valueOf(streamId);
        }
    }

    private static int extractStreamId(Object target) {
        if (target instanceof HttpObject) {
            return ((HttpObject) target).streamId();
        }
        if (target == null) {
            return -1;
        }

        Integer streamId = invokeIntAccessor(target, "streamId");
        if (streamId != null) {
            return streamId;
        }
        streamId = invokeIntAccessor(target, "getStreamId");
        return streamId == null ? -1 : streamId;
    }

    private static Integer invokeIntAccessor(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
        } catch (Throwable e) {
            return null;
        }
        return null;
    }
}