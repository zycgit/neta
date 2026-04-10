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
package net.hasor.neta.codec.http.websocket;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpScope;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.neta.codec.http.h2.Http2ResetEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketCloseActionDispatchTest extends AbstractWebSocketTest {
    @Test
    public void testHttp1TerminateClosesConnection() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.context(HttpVersion.class, HttpVersion.HTTP_1_1);
                ctx.context(HttpScope.class, HttpScope.CONNECTION);
                ctx.addLastDecoder("trigger", closeTrigger(WebSocketCloseType.TERMINATE, null));
            }, VrtSoConfig.asServer());

            receiveAndIntBound(pipe, textFrame("hello"));
            assertTrue(waitUntil(() -> pipe.channel().isClose(), 1000L));
            assertNull(findEvent(pipe.channelEvents(), Http2ResetEvent.class));
        });
    }

    @Test
    public void testHttp1SendCloseAndTerminateWaitsForFutureAndClosesConnection() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<BasicFuture<Object>> futureRef = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.context(HttpVersion.class, HttpVersion.HTTP_1_1);
                ctx.context(HttpScope.class, HttpScope.CONNECTION);
                ctx.addLastDecoder("trigger", closeTrigger(WebSocketCloseType.SEND_CLOSE_AND_TERMINATE, futureRef));
            }, VrtSoConfig.asServer());

            receiveAndIntBound(pipe, textFrame("hello"));
            assertFalse(pipe.channel().isClose());

            BasicFuture<Object> future = futureRef.get();
            assertNotNull(future);
            future.completed(null);
            assertTrue(waitUntil(() -> pipe.channel().isClose(), 1000L));
            assertNull(findEvent(pipe.channelEvents(), Http2ResetEvent.class));
        });
    }

    @Test
    public void testHttp2StreamTerminatePublishesResetEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.context(HttpVersion.class, HttpVersion.HTTP_2_0);
                ctx.context(HttpScope.class, HttpScope.STREAM);
                ctx.context(PartitionKey.class, PartitionKey.newKey(7));
                ctx.addLastDecoder("trigger", closeTrigger(WebSocketCloseType.TERMINATE, null));
            }, VrtSoConfig.asServer());

            receiveAndIntBound(pipe, textFrame("hello"));
            assertTrue(waitUntil(() -> findEvent(pipe.channelEvents(), Http2ResetEvent.class) != null, 1000L));

            Http2ResetEvent event = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
            assertNotNull(event);
            assertEquals(7, event.streamId());
            assertFalse(event.isRemote());
            assertFalse(pipe.channel().isClose());
        });
    }

    @Test
    public void testHttp2SendCloseAndTerminateWaitsForFuture() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<BasicFuture<Object>> futureRef = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.context(HttpVersion.class, HttpVersion.HTTP_2_0);
                ctx.context(HttpScope.class, HttpScope.STREAM);
                ctx.context(PartitionKey.class, PartitionKey.newKey(9));
                ctx.addLastDecoder("trigger", closeTrigger(WebSocketCloseType.SEND_CLOSE_AND_TERMINATE, futureRef));
            }, VrtSoConfig.asServer());

            receiveAndIntBound(pipe, textFrame("hello"));
            assertNull(findEvent(pipe.channelEvents(), Http2ResetEvent.class));

            BasicFuture<Object> future = futureRef.get();
            assertNotNull(future);
            future.completed(null);
            assertTrue(waitUntil(() -> findEvent(pipe.channelEvents(), Http2ResetEvent.class) != null, 1000L));

            Http2ResetEvent event = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
            assertNotNull(event);
            assertEquals(9, event.streamId());
            assertFalse(pipe.channel().isClose());
        });
    }

    private ProtoHandler<HttpObject, HttpObject> closeTrigger(WebSocketCloseType closeType, AtomicReference<BasicFuture<Object>> futureRef) {
        return new ProtoHandler<HttpObject, HttpObject>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
                while (src.hasMore()) {
                    HttpObject msg = src.takeMessage();
                    if (msg != null) {
                        msg.release();
                    }
                }

                BasicFuture<Object> future = futureRef != null ? new BasicFuture<>() : null;
                if (futureRef != null) {
                    futureRef.set(future);
                }
                InnelUtils.executeCloseAction(context, closeType, future);
                return ProtoStatus.Next;
            }
        };
    }

    private static <T> T findEvent(Iterable<SoEvent> events, Class<T> eventType) {
        if (events == null) {
            return null;
        }

        for (SoEvent event : events) {
            if (event != null && eventType == event.getEventType()) {
                return eventType.cast(event.getData());
            }
        }
        return null;
    }
}