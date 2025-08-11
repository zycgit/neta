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

import net.hasor.neta.channel.SoConfig;

/**
 * Listener options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class UdpSoConfig extends SoConfig {
    private Integer rcvPacketSize;
    //SO_REUSEADDR	重复使用地址
    //SO_BROADCAST	允许传输广播数据报
    //IP_TOS	互联网协议 (IP) 标头中的服务类型 (ToS) 八位字节
    //IP_MULTICAST_IF	网际协议 (IP) 多播数据报的网络接口
    //IP_MULTICAST_TTL	time-to-live 用于 Internet 协议 (IP) 多播数据报
    //IP_MULTICAST_LOOP	互联网协议 (IP) 多播数据报的环回

    public UdpSoConfig() {
        super(UdpProvider.NAME);
    }

    public Integer getRcvPacketSize() {
        return this.rcvPacketSize;
    }

    public void setRcvPacketSize(Integer rcvPacketSize) {
        this.rcvPacketSize = rcvPacketSize;
    }
}