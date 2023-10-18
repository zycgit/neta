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
package net.hasor.cobble.net.channel;
import net.hasor.cobble.concurrent.future.Future;

/**
 * 管道上下文
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public interface PipeContext {

    /** 配置 */
    SoConfig getConfig();

    /** 通道 */
    NetChannel channel();

    /** 递交异步任务 */
    <T> Future<T> submitSoTask(AbstractSoTask mainTask, T result);

    <T> T context(Class<T> attachment);

    <T> T context(Class<T> attachmentType, T attachment);

    void clearFlash();

    SoResManager getSoResManager();
}