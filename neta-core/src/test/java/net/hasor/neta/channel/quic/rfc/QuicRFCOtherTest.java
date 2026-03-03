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
package net.hasor.neta.channel.quic.rfc;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.quic.QuicChannel;
import net.hasor.neta.channel.quic.QuicSoConfig;
import net.hasor.neta.channel.quic.QuicStreamChannel;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * QUIC RFC 合规测试 — 其它场景（关闭后行为、PING 超时）。
 * <p>
 * 验证连接生命周期边界条件和异常路径：
 * <ul>
 *   <li>§10.2 关闭后新建流/发送数据应立即失败（B1-2）</li>
 *   <li>§12 / §19.2 PING 超时后 Future 以异常完成（C1-1）</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCOtherTest extends AbstractSoTest {

    // ════════════════════════════════════════════════════════════════════
    //  B1-2: 连接关闭后操作失败 — RFC 9000 §10.2
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：连接 closeGracefully() 后，尝试新建流应以异常失败。
     * <p>
     * 验证关闭后资源不可再使用。
     */
    @Test
    public void testPostCloseNewStreamFails() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();

        // 先关闭连接
        clientConn.closeGracefully().get();
        Thread.sleep(100);

        // 尝试新建流 — 期望失败
        boolean failed = false;
        try {
            Future<QuicStreamChannel> streamFuture = clientConn.newBidiStream();
            QuicStreamChannel stream = streamFuture.get();
            // 即使 newBidiStream 返回了 future，get() 应抛异常
            // 或者 stream 上的 write 应失败
            if (stream != null) {
                try {
                    stream.sendData("test".getBytes());
                } catch (Exception e) {
                    failed = true;
                }
            }
        } catch (Exception e) {
            failed = true; // newBidiStream 直接抛异常
        }
        assert failed : "newBidiStream() or write on closed connection must fail";

        neta.shutdown();
    }

    /**
     * 场景：连接关闭后，ping() 应以异常完成（不挂起）。
     * <p>
     * 与 {@code QuicSimplyTest.testConnectionGracefulClose()} 中的内联验证不同，
     * 本测试更关注 PING 的 Future 行为而非握手/关闭流程。
     */
    @Test
    public void testPostClosePingFails() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();

        // 关闭连接
        clientConn.closeGracefully().get();
        Thread.sleep(100);

        // ping on closed connection → 应以异常完成而非挂起
        boolean pingFailed = false;
        try {
            clientConn.ping(500).get();
        } catch (Exception e) {
            pingFailed = true;
        }
        assert pingFailed : "ping() on closed connection must fail immediately or with timeout";

        neta.shutdown();
    }

    // ════════════════════════════════════════════════════════════════════
    //  C1-1: PING 超时 — RFC 9000 §12 / §19.2
    // ════════════════════════════════════════════════════════════════════

    /**
     * 场景：对已关闭连接调用 ping(100)，Future 以异常失败
     * （而非无限挂起），验证超时或关闭后的及时资源释放。
     * <p>
     * 此测试与 B1-2 的 {@code testPostClosePingFails} 互补：B1-2
     * 使用较长超时（500ms），C1-1 使用极短超时（100ms）验证边界。
     */
    @Test
    public void testPingTimeoutOnClosedConnection() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicCfg = quicConfig();
        neta.bind(address, serverProto, quicCfg);

        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, clientProto, quicCfg).get();
        clientConn.closeGracefully().get();
        Thread.sleep(100);

        long start = System.currentTimeMillis();
        boolean failed = false;
        try {
            clientConn.ping(100).get();
        } catch (Exception e) {
            failed = true;
        }
        long elapsed = System.currentTimeMillis() - start;

        assert failed : "ping(100) on closed connection must fail";
        // 不应该等满 100ms（关闭后应立即/很快失败）
        // 但也允许合理的调度延迟（500ms 上限）
        assert elapsed < 500 : "ping on closed conn should fail quickly, but took " + elapsed + "ms";

        neta.shutdown();
    }
}
