/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.tconsole.launcher.telnet;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.tconsole.TelOptions;
import net.hasor.tconsole.launcher.TelSessionObject;
import net.hasor.tconsole.launcher.TelUtils;

/**
 * Handles a server-side channel.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
class SocketTelHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private static final Logger     logger = LoggerFactory.getLogger(SocketTelHandler.class);
    private final Predicate<String> inBoundMatcher;
    private final SocketTelService  telContext;
    private final ExecutorService   executor;
    private final ByteBuf           dataReader;

    SocketTelHandler(SocketTelService telContext, ExecutorService executor, Predicate<String> inBoundMatcher) {
        this.executor = executor;
        this.inBoundMatcher = inBoundMatcher == null ? s -> true : inBoundMatcher;
        this.telContext = telContext;
        this.dataReader = ByteBufAllocator.DEFAULT.buffer();
    }

    @Override
    public void onActive(ProtoContext ctx) throws Throwable {
        NetChannel channel = (NetChannel) ctx.getChannel();
        InetSocketAddress remoteAddr = (InetSocketAddress) channel.getRemoteAddr();
        InetSocketAddress localAddr = (InetSocketAddress) channel.getLocalAddr();
        String remoteAddrStr = remoteAddr.getAddress().getHostAddress();

        // .不允许连入的情况
        if (!this.inBoundMatcher.test(remoteAddrStr)) {
            this.printReject(ctx, remoteAddr, localAddr);
            channel.close();
            return;
        }
        logger.info("tConsole -> accept inBound socket ,remoteAddress = " + remoteAddrStr);

        // .构造会话
        SocketWriter dataWriter = new SocketWriter(channel);
        TelSessionObject sessionObj = new TelSessionObject(this.telContext, this.dataReader, dataWriter) {
            public boolean isClose() {
                return dataWriter.isClose();
            }
        };
        sessionObj.setAttribute(TelOptions.SILENT, this.telContext.isSilent());
        sessionObj.setAttribute(TelOptions.ENDCODE_OF_SILENT, this.telContext.encodedOfSilent());
        ctx.context(TelSessionObject.class, sessionObj);
        ctx.context(SocketWriter.class, dataWriter);

        // .异步延迟 200ms 打印欢迎信息，留给例如 silent 生效空间。
        this.executor.submit(() -> {
            try {
                Thread.sleep(200);
                printWelcome(ctx, remoteAddr, localAddr);
            } catch (Exception e) {
                /**/ }
        });

        // .创建Session
        logger.info("tConsole -> trigger TelSessionListener.sessionCreated");
    }

    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws IOException {
        TelSessionObject telSession = ctx.context(TelSessionObject.class);
        SocketWriter dataWriter = ctx.context(SocketWriter.class);

        while (src.hasMore()) {
            ByteBuf buf = src.takeMessage();
            if (buf.readableBytes() == 5) {
                byte[] bytes = new byte[5];
                buf.getBytes(0, bytes);
                if (Objects.deepEquals(bytes, new byte[] { (byte) -1, (byte) -12, (byte) -1, (byte) -3, (byte) 6 })) {
                    logger.info("tConsole -> ^C exit");
                    ctx.getChannel().close();
                    return ProtoStatus.Next;
                }
            }

            this.dataReader.writeBuffer(buf);
            this.dataReader.flush();
            buf.free();
        }

        // do tryReceiveEvent
        int lastBufferSize = this.dataReader.readableBytes();
        while (telSession.tryReceiveEvent()) {
            if (lastBufferSize == this.dataReader.readableBytes()) {
                break;
            }
            lastBufferSize = this.dataReader.readableBytes();
        }

        String responseMessage = dataWriter.drainMessage();
        boolean closeSession = dataWriter.isCloseRequested() || TelUtils.aBoolean(telSession, TelOptions.CLOSE_SESSION);

        // 输出
        if (!closeSession && this.dataReader.readableBytes() == 0) {
            boolean noSilent = !TelUtils.aBoolean(telSession, TelOptions.SILENT);
            if (noSilent) {
                if (StringUtils.isBlank(responseMessage)) {
                    responseMessage = TelUtils.CMD;
                }
            } else {
                String codeOfSilent = TelUtils.aString(telSession, TelOptions.ENDCODE_OF_SILENT);
                if (StringUtils.isNotBlank(codeOfSilent)) {
                    responseMessage = responseMessage + codeOfSilent + "\n";
                }
            }
        }

        if (StringUtils.isNotBlank(responseMessage)) {
            ctx.sendData(responseMessage.getBytes());
            if (closeSession) {
                ctx.getChannel().close();
            }
        } else if (closeSession) {
            ctx.getChannel().close();
        }

        return ProtoStatus.Next;
    }

    private void printReject(ProtoContext ctx, InetSocketAddress remoteAddr, InetSocketAddress localAddr) throws IOException {
        String remoteAddress = remoteAddr.getAddress().getHostAddress();
        logger.warn("tConsole -> reject inBound socket ,remoteAddress = " + remoteAddress);
        this.outString(ctx, "--------------------------------------------\r\n\r\n");
        this.outString(ctx, "I'm sorry you are not allowed to connect tConsole.\r\n\r\n");
        this.outString(ctx, " your address is :" + remoteAddress + "\r\n");
        this.outString(ctx, "--------------------------------------------\r\n");
    }

    private void printWelcome(ProtoContext ctx, InetSocketAddress remoteAddr, InetSocketAddress localAddr) throws IOException {
        TelSessionObject telSession = ctx.context(TelSessionObject.class);
        if (TelUtils.aBoolean(telSession, TelOptions.SILENT)) {
            logger.info("tConsole -> silent, ignore Welcome info.");
            return;
        }
        logger.info("tConsole -> send Welcome info.");
        // Send greeting for a new connection.
        this.outString(ctx, "--------------------------------------------\r\n\r\n");
        this.outString(ctx, "Welcome to tConsole!\r\n");
        this.outString(ctx, "\r\n");
        this.outString(ctx, "     login : " + new Date() + " now. form " + remoteAddr + "\r\n");
        this.outString(ctx, "    workAt : " + localAddr + "\r\n");
        this.outString(ctx, "Tips: You can enter a 'help' or 'help -a' for more information.\r\n");
        this.outString(ctx, "use the 'exit' or 'quit' out of the console.\r\n");
        this.outString(ctx, "--------------------------------------------\r\n");
        this.outString(ctx, TelUtils.CMD);
    }

    private Future<?> outString(ProtoContext ctx, String message) throws IOException {
        return ctx.sendData(message.getBytes());
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
        logger.error("tConsole error->" + e.getMessage(), e);
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        logger.info("tConsole -> trigger TelSessionDestroyListener.sessionDestroyed");
    }
}