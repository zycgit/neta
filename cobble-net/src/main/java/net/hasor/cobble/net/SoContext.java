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
 * 管理所有网络链接和状态
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SoContext {
    /** 配置 */
    SoConfig getConfig();

    /** 全局资源管理器 */
    SoResManager getResourceManager();

    /** 异步方式处理 swap 区到 rcv/snd 区的 IO 操作任务 */
    <T> Future<T> submitSoTask(AbstractSoTask mainTask, T result);

    /** 异步方式处理 swap 区到 rcv/snd 区的 IO 操作任务 */
    <T> Future<T> submitSoTask(SoResManager rm, AbstractSoTask mainTask, T result);

    /** Socket 通道是否已经关闭 */
    boolean isClose(long channelID);

    /** 立即触发 channel 的关闭，相当于 {@link NetChannel#closeNow()} */
    void closeChannel(long channelID, String message);
}