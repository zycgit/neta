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
package net.hasor.neta.handler;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtil;
import net.hasor.neta.channel.*;

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

        //  Net      SSL        Frame        Req/Res
        // Bytes -> Bytes -> TypeFrame -> TypeRequest
        // Bytes <- Bytes <- TypeFrame <- TypeResponse
        PipeConfig pipeConfig = new PipeConfig();
        PipeStackFactory stackFactory = new PipeInitializer()
                // Bytes <-> TypeFrame
                .nextTo("TypeFrame", pipeConfig, PipeLayerServerTest::doDecoder1, PipeLayerServerTest::doEncoder1)
                // TypeFrame -> TypeRequest and TypeResponse -> TypeFrame
                .nextTo("TypeRequest/Response", pipeConfig, PipeLayerServerTest::doDecoder2, PipeLayerServerTest::doEncoder2)
                // process
                .bindReceive(PipeLayerServerTest::onData)
                // create Stack
                .buildFactory();

        try (CobbleSocket socket = new CobbleSocket(config)) {
            NetListen listen = socket.listen("127.0.0.1", 5567, stackFactory);
            onListen(listen);
        }
    }

    /** 开始监听 */
    private static void onListen(NetListen netListen) {
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

    /** 消息：解码L1 bytes -> TypeFrame */
    public static PipeStatus doDecoder1(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<TypeFrame> dst) {
        ByteBuf byteBuf = src.takeMessage();
        String line;
        do {
            line = byteBuf.readLine();
            if (line != null) {
                dst.offerMessage(new TypeFrame(line));
            }
        } while (line != null && dst.hasSlot());

        byteBuf.markReader();

        src.rcvSubmit();
        dst.sndSubmit();
        return PipeStatus.Success;
    }

    /** 消息：编码L1 TypeFrame -> bytes */
    public static PipeStatus doEncoder1(PipeContext context, PipeRcvQueue<TypeFrame> src, PipeSndQueue<ByteBuf> dst) {
        while (src.hasMore()) {
            TypeFrame message = src.peekMessage();
            byte[] bytes = message.getMessage().getBytes();

            ByteBuf wrap = ByteBufAllocator.DEFAULT.wrap(bytes);
            wrap.markWriter();
            dst.offerMessage(wrap);

            src.rcvSubmit();
            dst.sndSubmit();
        }

        return PipeStatus.Success;
    }

    /** 消息：解码L2 TypeFrame -> TypeRequest */
    public static PipeStatus doDecoder2(PipeContext context, PipeRcvQueue<TypeFrame> src, PipeSndQueue<TypeRequest> dst) {
        TypeFrame frame;
        do {
            frame = src.takeMessage();
            if (frame != null) {
                dst.offerMessage(new TypeRequest(frame.getMessage()));
            }
        } while (frame != null && dst.hasSlot());

        src.rcvSubmit();
        dst.sndSubmit();
        return PipeStatus.Success;
    }

    /** 消息：编码L2 TypeResponse -> TypeFrame */
    public static PipeStatus doEncoder2(PipeContext context, PipeRcvQueue<TypeResponse> src, PipeSndQueue<TypeFrame> dst) {
        TypeResponse response;
        do {
            response = src.takeMessage();
            if (response != null) {
                dst.offerMessage(new TypeFrame(response.getMessage()));
            }
        } while (response != null && dst.hasSlot());

        src.rcvSubmit();
        dst.sndSubmit();
        return PipeStatus.Success;
    }

    /** 消息：处理 */
    private static void onData(PipeContext context, PipeRcvQueue<TypeRequest> data) {
        NetChannel channel = context.channel();

        while (true) {
            TypeRequest line = data.takeMessage();
            if (line != null) {
                System.out.println("rcvChannel " + channel.getChannelID() + ", data=" + line);
                String echoMessage = "echo " + line.getMessage() + "\n";
                channel.sendData(new TypeResponse(echoMessage));
                data.rcvSubmit();
            } else {
                break;
            }
        }
    }

    //    public void abc() {

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

