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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
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

        PipeConfig pipeConfig = new PipeConfig();
        PipeStackFactory stackFactory = new PipeInitializer()
                // decoder encoder
                .nextTo(pipeConfig, PipeLayerServerTest::doDecoder, PipeLayerServerTest::doEncoder)
                // message
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

    /** 消息：解码 */
    public static PipeStatus doDecoder(PipeContext context, ByteBuf src, PipeSndQueue<String> dst) {
        String line;
        do {
            line = src.readLine();
            if (line != null) {
                dst.offerMessage(line);
            }
        } while (line != null && dst.hasSlot());

        src.markReader();
        dst.sndSubmit();
        return PipeStatus.Success;
    }

    /** 消息：编码 */
    public static PipeStatus doEncoder(PipeContext context, PipeRcvQueue<String> src, ByteBuf dst) {
        while (src.hasMore() && dst.hasWritable()) {
            String message = src.peekMessage();
            byte[] bytes = message.getBytes();
            if (dst.writableBytes() < bytes.length) {
                break;
            }

            dst.writeBytes(bytes);
            src.skipMessage(1);
            src.rcvSubmit();
            dst.markWriter();
        }

        return PipeStatus.Success;
    }

    /** 消息：处理 */
    private static void onData(PipeContext context, PipeRcvQueue<String> data) {
        NetChannel channel = context.channel();

        while (true) {
            String line = data.takeMessage();
            if (StringUtils.isNotBlank(line)) {
                System.out.println("rcvChannel " + channel.getChannelID() + ", data=" + line);
                String echoMessage = "echo " + line + "\n";
                channel.sendData(echoMessage);
                data.rcvSubmit();
            } else {
                break;
            }
        }
    }

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

