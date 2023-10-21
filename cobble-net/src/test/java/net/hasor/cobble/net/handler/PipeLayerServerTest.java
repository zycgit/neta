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
package net.hasor.cobble.net.handler;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.net.bytebuf.ByteBuf;
import net.hasor.cobble.net.bytebuf.ByteBufUtil;
import net.hasor.cobble.net.channel.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeLayerServerTest {
    public static void main(String[] args) throws Exception {
        SoConfig config = new SoConfig();
        config.setSwapBuf(2, 2);
        config.setLocalBuf(32, 32);
        //        config.setSoReadTimeoutMs(6000);
        //        config.setSoKeepAlive(true);
        //        config.setSoKeepIntervalSec(10);
        //        config.setSoKeepIdleSec(10);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        //
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ThreadFactory tf1 = ThreadUtils.threadFactory(loader, "IO-Thread-%s", true);
        ThreadFactory tf2 = ThreadUtils.threadFactory(loader, "WORK-Thread-%s", true);
        config.setIoExecutor(Executors.newFixedThreadPool(1, tf1));
        config.setTaskExecutorFactory((cfg, ctxName) -> Executors.newFixedThreadPool(1, tf2));

        PipeBuilder pipeBuilder = new PipeInitializer();
        PipeConfig pipeConfig = new PipeConfig();
        PipeStackFactory stackFactory = pipeBuilder.nextTo(pipeConfig, new StringRead(), new StringWrite()).buildFactory();

        try (CobbleSocket socket = new CobbleSocket(config)) {

            NetListen listen = socket.listen("127.0.0.1", 5567, stackFactory);
            read(listen);
        }
    }

    private static void read(NetListen netListen) {
        try {
            System.out.println("10s after suspend.");
            ThreadUtils.sleep(10000);
            netListen.suspend();

            System.out.println("5s after resume.");
            ThreadUtils.sleep(5000);
            netListen.resume();

            System.out.println("resume.");
            System.in.read();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //
    //
    //
    //    ByteBuf buf = ByteBufAllocator.DEFAULT.wrap();
    //                buf.markWriter();
    //                System.out.println("rcvChannel " + pipeContext.channel().getChannelID() + ", data=" + line);
    //          pipeContext.channel().sendData("hello");

    static class StringRead implements PipeBytesToMessageHandler<String> {

        @Override
        public PipeStatus doHandler(PipeContext context, ByteBuf src, PipeSndQueue<String> dst) {
            String line;
            do {
                line = src.readLine();
                if (line != null) {
                    dst.offerMessage(line);
                }
            } while (line != null && dst.hasSlot());

            src.markReader();
            dst.sndMark();
            return PipeStatus.Finish;
        }
    }

    static class StringWrite implements PipeMessageToBytesHandler<String> {

        @Override
        public PipeStatus doHandler(PipeContext context, PipeRcvQueue<String> src, ByteBuf dst) {
            while (src.hasMore() && dst.hasWritable()) {
                String message = src.peekMessage();
                byte[] bytes = ("echo " + message + "\n").getBytes();
                if (dst.writableBytes() < bytes.length) {
                    break;
                }

                dst.writeBytes(bytes);
                src.skipMessage(1);
                src.rcvMark();
                dst.markWriter();
            }

            return PipeStatus.Finish;
        }
    }
    //        //        ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + writeData + "\n").getBytes());
    //        //        buf.markWriter();
    //        //        return new ByteBuf[] { buf };

    //        String line = rcvByteBuf.readLine();
    //        rcvByteBuf.markReader();
    //
    //        if (line != null) {
    //            ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + line + "\n").getBytes());
    //            buf.markWriter();
    //            System.out.println("rcvChannel " + pipeContext.channel().getChannelID() + ", data=" + line);
    //            pipeContext.channel().sendData("hello");
    //            return new ByteBuf[] { buf };
    //        }
    //
    //
    //
    //    class TypeRequest {
    //
    //    }
    //
    //    class TypeResponse {
    //
    //    }
    //
    //    class TypeFrame {
    //
    //    }

    //    public void abc() {
    //        //  Net      SSL        Frame        Req/Res
    //        // Bytes -> Bytes -> TypeFrame -> TypeRequest
    //        // Bytes <- Bytes <- TypeFrame <- TypeResponse
    //
    //        PipeBuilder builder = new PipeInitializer();
    //        PipeConfig config = null;
    //
    //        PipeLayerStack layerStack = builder.nextTo(config, new PipeBytesToBytesLayer() {
    //            @Override
    //            public PipeStatus doLayer(PipeContext context, boolean isRcv, ByteBuf rcvUp, ByteBuf rcvDown, ByteBuf sndUp, ByteBuf sndDown) throws IOException {
    //                // SSL
    //                return null;
    //            }
    //        }).nextTo(config, new PipeBytesToMessageLayer<TypeFrame, TypeFrame>() {
    //            @Override
    //            public PipeStatus doLayer(PipeContext context, boolean isRcv, ByteBuf rcvUp, PipeSndQueue<TypeFrame> rcvDown, PipeRcvQueue<TypeFrame> sndUp, ByteBuf sndDown) throws IOException {
    //                // RSocket
    //                return null;
    //            }
    //        }).nextTo(config, new PipeMessageToMessageLayer<TypeFrame, TypeRequest, TypeResponse, TypeFrame>() {
    //            @Override
    //            public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<TypeFrame> rcvUp, PipeSndQueue<TypeRequest> rcvDown, PipeRcvQueue<TypeResponse> sndUp, PipeSndQueue<TypeFrame> sndDown) throws IOException {
    //                // req/res
    //                return null;
    //            }
    //        }).build();
    //
    //    }

}

