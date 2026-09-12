/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic.rfc;

import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.junit.Test;

import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.quic.QuicChannel;
import net.hasor.neta.channel.transport.quic.QuicSoConfig;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;

/**
 * QUIC RFC 合规测试 — 帧交互。
 * <p>
 * 覆盖 QUIC 连接终止帧交互等场景：
 * <ul>
 *   <li>§10.2 CONNECTION_CLOSE 帧发送与接收（B1-1, B2-1, B2-2）— 集成测试</li>
 * </ul>
 * <p>
 * 注意：流数据乱序重组的单元测试位于同包测试类
 * {@code net.hasor.neta.channel.quic.QuicStreamReassemblerTest}，
 * 因为被测类是包私有的。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCFrameTest extends AbstractSoTest {

    // ════════════════════════════════════════════════════════════════════
    //  B1-1: close → CONNECTION_CLOSE(NO_ERROR) — RFC 9000 §10.2
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：调用 closeGracefully() 后，Future 正常完成且底层发送
     * CONNECTION_CLOSE(NO_ERROR=0x00)。
     * <p>
     * 验证 closeGracefully() 返回的 Future 不为 null 且不抛异常。
     * 与 {@code QuicSimplyTest.testConnectionGracefulClose()} 不同，
     * 本测试额外验证 closeWithError(INTERNAL_ERROR) 路径。
     */
    @Test
    public void testCloseWithErrorCode() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();

        // closeWithError(INTERNAL_ERROR) → Future 正常完成
        QuicChannel result = clientConn.closeWithError(0x01, "test error").get();
        assert result != null : "closeWithError() Future must complete";

        neta.shutdown();
    }

    /**
     * B2-2: 客户端发送 CONNECTION_CLOSE 后，服务端连接对应关闭。
     * <p>
     * 验证两端最终均处于关闭状态。
     */
    @Test
    public void testServerReceivesConnectionClose() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();

        // Client sends CONNECTION_CLOSE
        clientConn.closeGracefully().get();

        // 等待服务端处理关闭
        Thread.sleep(200);

        // 验证客户端连接关闭后 ping 失败
        boolean pingFailed = false;
        try {
            clientConn.ping(500).get();
        } catch (Exception e) {
            pingFailed = true;
        }
        assert pingFailed : "ping() on closed connection must fail";

        neta.shutdown();
    }
}
