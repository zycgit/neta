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
package net.hasor.neta.channel.transport.quic;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * QUIC 传输层集成测试（非 TLS 明文模式）。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSimplyTest extends AbstractSoTest {
    /**
     * 场景：验证 QUIC 握手能够正常完成。
     * <p>
     * client 调用 {@code connectAsync} 后，返回的 {@link NetChannel} 必须是
     * {@link QuicChannel} 实例，表明 QUIC 握手（Initial/Handshake/1-RTT）已全部完成。
     */
    @Test
    public void testHandshakeCompletes() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();

        // Bind server
        neta.bind(address, serverProto, quicCfg);

        // Client connect → future resolves with QuicChannel after handshake
        Future<NetChannel> connectFuture = neta.connectAsync(address, clientProto, quicCfg);
        NetChannel clientConn = connectFuture.get();

        assert clientConn instanceof QuicChannel : "Expected QuicChannel after handshake, got: " + clientConn.getClass().getSimpleName();

        neta.shutdown();
    }

    /**
     * 场景：验证 QUIC 连接优雅关闭（RFC 9000 §10.2）。
     */
    @Test
    public void testConnectionGracefulClose() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        Future<NetChannel> connectFuture = neta.connectAsync(address, clientProto, quicCfg);
        QuicChannel clientConn = (QuicChannel) connectFuture.get();

        // 1. 优雅关闭，Future 应正常完成（不抛出异常）
        QuicChannel closed = clientConn.closeGracefully().get();
        assert closed != null : "closeGracefully() Future must complete";

        // 2. 关闭后 ping() 应立即失败（连接不可用）
        boolean pingFailed = false;
        try {
            clientConn.ping(1000).get();
        } catch (Exception e) {
            pingFailed = true; // IllegalStateException 或 ExecutionException 均可
        }
        assert pingFailed : "ping() after closeGracefully() must fail";

        neta.shutdown();
    }

    /**
     * 场景：验证 PING/ACK RTT 测量（RFC 9000 §12 / §19.2）。
     */
    @Test
    public void testPingRtt() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();

        // 发送 PING，等待 ACK，取回 RTT（毫秒）
        // 注意：在组合测试的高负载环境下，IO 线程调度可能出现短暂延迟，
        //       最多重试 5 次（每次超时 500 ms），正常回环延迟应 < 1 ms。
        Long rtt = null;
        for (int attempt = 0; attempt < 5 && rtt == null; attempt++) {
            try {
                rtt = clientConn.ping(500).get();
            } catch (java.util.concurrent.ExecutionException ex) {
                if (!(ex.getCause() instanceof java.util.concurrent.TimeoutException)) {
                    throw ex; // 非超时异常直接抛
                }
                // 超时重试
            }
        }
        assert rtt != null : "ping() must complete within 5 attempts (each timeout=500ms)";
        assert rtt >= 0 : "RTT must be >= 0, got: " + rtt;

        neta.shutdown();
    }

}
