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
package net.hasor.neta.channel.udp;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.hasor.neta.channel.AbstractSoTest.safePort;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoUdpReadTest2 {
    @Test
    public void udpRead_Test() throws IOException {
        // 创建UDP套接字
        DatagramSocket socket = new DatagramSocket();

        // 目标地址和端口
        InetAddress address = InetAddress.getByName("127.0.0.1");
        int port = 24601; // 替换为实际目标端口

        // 准备发送的数据
        String message = "Hello UDP";
        byte[] sendData = message.getBytes(StandardCharsets.UTF_8);

        int i = 0;
        while (true) {
            i++;
            if (i > 50) {
                break;
            }
            ThreadUtils.sleep(1000);
            // 创建数据包并发送
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
        }

        // 关闭套接字
        socket.close();
    }
    //
    //    @Test
    //    public void udpRead_Test2() throws IOException {
    //        int safePort = safePort();
    //        System.out.println(safePort);
    //        ExecutorService executor = Executors.newFixedThreadPool(2);
    //        Selector selector = Selector.open();
    //
    //        // 创建并配置DatagramChannel
    //        DatagramChannel channel = DatagramChannel.open();
    //        channel.configureBlocking(false);
    //        channel.socket().bind(new InetSocketAddress("127.0.0.1", safePort));  // 绑定到任意可用端口
    //
    //        // 注册通道到选择器上，设置为读模式
    //        channel.register(selector, SelectionKey.OP_READ);
    //
    //        // 模拟接收数据的处理逻辑
    //        while (true) {
    //            if (selector.select() == 0)
    //                continue;  // 如果没有事件发生，则继续循环
    //
    //            for (SelectionKey key : selector.selectedKeys()) {
    //                if (key.isReadable()) {
    //                    try {
    //                        ByteBuffer buffer = ByteBuffer.allocate(1024);
    //
    //                        channel.receive(buffer);
    //                        buffer.flip();
    //                        if (buffer.remaining() > 0) {
    //                            byte[] data = new byte[buffer.remaining()];
    //                            System.arraycopy(buffer.array(), 0, data, 0, buffer.remaining());
    //                            String receivedMessage = new String(data, "UTF-8");
    //                            System.out.println("Received: " + receivedMessage);
    //                        }
    //                    } catch (IOException e) {
    //                        e.printStackTrace();
    //                    }
    //                }
    //            }
    //            selector.selectedKeys().clear();  // 清除已处理的选择键
    //        }
    //    }

}