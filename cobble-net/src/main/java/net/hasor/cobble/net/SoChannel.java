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
package net.hasor.cobble.net;

import net.hasor.cobble.concurrent.future.Future;

/**
 * 通道
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SoChannel<T> {
    /** Socket 连接通道 ID */
    long getChannelID();

    /** 连接建立时间 */
    long getCreatedTime();

    /** 最后一次活跃时间 */
    long getLastActiveTime();

    /** 侦听类型 Channel */
    boolean isListen();

    /** 由远程发起的 Channel */
    boolean isServer();

    /** 由本地发起的 Channel */
    boolean isClient();

    /**
     * 关闭这个通道的新事件，在所有事件处理完毕之后关闭这个通道
     *
     * <li>对于 Listen 通道，会关闭监听。</li>
     * <li>对于 Socket 通道，触发 Socket 关闭，待所有数据写入完成后在关闭。</li>
     *
     * <p>方法 {@link #closeNow()} 和 {@link #close()} 第一个被调用的有效</p>
     */
    Future<T> close();

    /** 立即关闭这个通道 */
    Future<T> closeNow();

    /** 是否位于关闭状态 */
    boolean isClose();
}