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
package net.hasor.neta.channel;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import org.junit.Test;

/**
 * Tests for {@link SoConfig} factory methods, getters and setters.
 * @author test
 */
public class SoConfigTest {

    @Test
    public void tcpFactory() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert cfg != null;
        assert "TCP".equals(cfg.getProtocol());
    }

    @Test
    public void udpFactory() {
        UdpSoConfig cfg = SoConfig.UDP();
        assert cfg != null;
        assert "UDP".equals(cfg.getProtocol());
    }

    @Test
    public void slotSizeDefaults() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert cfg.getRcvSlotSize() == -1;
        assert cfg.getSndSlotSize() == -1;
    }

    @Test
    public void slotSizeSetters() {
        TcpSoConfig cfg = SoConfig.TCP();
        cfg.setRcvSlotSize(100);
        cfg.setSndSlotSize(200);
        assert cfg.getRcvSlotSize() == 100;
        assert cfg.getSndSlotSize() == 200;
    }

    @Test
    public void suspendDefaultFalse() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert !cfg.isSuspend();
        cfg.setSuspend(true);
        assert cfg.isSuspend();
    }

    @Test
    public void socketBufferSizes() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert cfg.getSoRcvBuf() == null;
        assert cfg.getSoSndBuf() == null;

        cfg.setSoBufSize(4096, 8192);
        assert cfg.getSoRcvBuf() == 4096;
        assert cfg.getSoSndBuf() == 8192;

        cfg.setSoRcvBuf(1024);
        assert cfg.getSoRcvBuf() == 1024;
        cfg.setSoSndBuf(2048);
        assert cfg.getSoSndBuf() == 2048;
    }

    @Test
    public void timeoutSettings() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert cfg.getSoReadTimeoutMs() == -1;
        assert cfg.getSoWriteTimeoutMs() == -1;

        cfg.setSoReadTimeoutMs(1000);
        cfg.setSoWriteTimeoutMs(2000);
        assert cfg.getSoReadTimeoutMs() == 1000;
        assert cfg.getSoWriteTimeoutMs() == 2000;
    }

    @Test
    public void connectTimeout() {
        TcpSoConfig cfg = SoConfig.TCP();
        assert cfg.getConnectTimeoutMs() == 10000;

        cfg.setConnectTimeoutMs(5000);
        assert cfg.getConnectTimeoutMs() == 5000;
    }
}
