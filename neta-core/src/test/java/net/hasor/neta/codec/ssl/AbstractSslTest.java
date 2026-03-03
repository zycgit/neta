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
package net.hasor.neta.codec.ssl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import net.hasor.cobble.function.EConsumer;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class AbstractSslTest {

    public static ProtoInitializer createProtoStack(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ProtoHelper.standard()
                // SSL
                .nextDuplex("SSL", new SslDuplexer(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    /**
     * Creates a protocol stack whose String codec layer listens for {@link SslEvent} and counts
     * down {@code handshakeLatch} as soon as TLS negotiation succeeds on that side.
     * <p>
     * {@link SslDuplexer} already calls {@code fireUserEvent(SslEvent.class, …)} the moment the
     * handshake finishes. That event propagates to every downstream handler in the pipeline, so
     * the String codec — which sits directly after the SSL layer — can intercept it without any
     * extra passthrough handler.
     * <pre>{@code
     * CountDownLatch handshakeDone = new CountDownLatch(2); // server side + client side
     * neta.bind(addr,        createProtoStackWithHandshakeLatch(serverConf, handshakeDone), config);
     * neta.connectSync(addr, createProtoStackWithHandshakeLatch(clientConf, handshakeDone), config);
     * handshakeDone.await(10, TimeUnit.SECONDS); // precise wait — no sleep needed
     * }</pre>
     */
    public static ProtoInitializer createProtoStackWithHandshakeLatch(SslConfig sslConf, CountDownLatch handshakeLatch) {
        return ctx -> {
            // SSL encryption/decryption layer — fires SslEvent when handshake completes
            ctx.addLast("SSL", new SslDuplexer(sslConf));
            // String codec layer — intercepts SslEvent to signal handshake completion,
            // then lets the event continue propagating (return false)
            ctx.addLast("String", new ProtoDuplexer<ByteBuf, String, String, ByteBuf>() {
                @Override
                public void onInit(ProtoContext context) {
                }

                @Override
                public void onActive(ProtoContext context) {
                }

                @Override
                public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) {
                    if (event.getEventType() == SslEvent.class && ((SslEvent) event.getData()).isHandshake()) {
                        handshakeLatch.countDown();
                    }
                    return false; // continue propagating
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<String> rcvDown, ProtoRcvQueue<String> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
                    if (isRcv) {
                        return doDecoder1(context, rcvUp, rcvDown);
                    } else {
                        return doEncoder1(context, sndUp, sndDown);
                    }
                }

                @Override
                public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                    return ProtoStatus.Next;
                }

                @Override
                public void onClose(ProtoContext context) {
                }
            });
        };
    }

    /**
     * Decoding the message: ByteBuf -> String
     */
    public static ProtoStatus doDecoder1(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<String> dst) {
        List<ByteBuf> bufArray = src.peekMessage(src.queueSize());
        if (bufArray == null || bufArray.isEmpty()) {
            return ProtoStatus.Next;
        }

        List<ByteBuf> temp = new ArrayList<>();
        boolean hasLine = false;
        for (ByteBuf buf : bufArray) {
            temp.add(buf);
            if (buf.hasLine()) {
                hasLine = true;
                break;
            }
        }
        if (!hasLine) {
            return ProtoStatus.Next;
        }

        ByteBuf tmpBuf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        int lastIndex = temp.size() - 1;
        for (int i = 0; i < temp.size(); i++) {
            ByteBuf buf = temp.get(i);

            if (i != lastIndex) {
                buf.readBuffer(tmpBuf);
                buf.markReader();
                src.skipMessage(1);
            } else {
                int expect = buf.expect('\n', StandardCharsets.US_ASCII);
                buf.readBuffer(tmpBuf, expect + 1);
                buf.markReader();
                if (buf.readableBytes() <= 0) {
                    src.skipMessage(1);
                }
            }
        }
        tmpBuf.markWriter();

        //
        String line = tmpBuf.readLine();
        if (line != null) {
            dst.offerMessage(line);
        }
        return ProtoStatus.Next;
    }

    /**
     * encoded message: String -> ByteBuf
     */
    public static ProtoStatus doEncoder1(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<ByteBuf> dst) {
        String message;
        do {
            message = src.takeMessage();
            if (message != null) {
                byte[] bytes = message.getBytes();
                if (bytes.length > 0) {
                    dst.offerMessage(ByteBuf.wrap(bytes));
                }
            }
        } while (message != null);
        return ProtoStatus.Next;
    }

    protected void autoCloseNeta(EConsumer<NetManager, Throwable> consumer) throws Throwable {
        NetManager neta = new NetManager();
        try {
            consumer.eAccept(neta);
        } finally {
            neta.shutdown();
        }
    }
}