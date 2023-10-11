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

import java.util.Queue;

/**
 * 发送上下文，涵盖了 SocketContext 对象和需要被发送的数据队列
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndContext {
    private final long             createdTime;
    private final SoContextImpl    context;
    private final SoResManager     rm;
    private final Queue<SoSndData> wQueue;

    public SoSndContext(long createdTime, SoContextImpl context, SoResManager rm, Queue<SoSndData> wQueue) {
        this.createdTime = createdTime;
        this.context = context;
        this.rm = rm;
        this.wQueue = wQueue;
    }

    public long getCreatedTime() {
        return this.createdTime;
    }

    public SoSndData popData() {
        return this.wQueue.poll();
    }

    public SoSndData peekData() {
        return this.wQueue.peek();
    }

    public SoContextImpl getContext() {
        return this.context;
    }

    public Future<?> submitTask(AbstractSoTask task, Object context) {
        return this.context.submitSoTask(this.rm, task, context);
    }
}
