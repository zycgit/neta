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
package net.hasor.neta.codec;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

/**
 * Transparent conveyor belt.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class TransparentPipeHandler<T> implements PipeHandler<T, T> {
    @Override
    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<T> src, PipeSndQueue<T> dst) {
        dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));
        return src.hasMore() && !dst.hasSlot() ? PipeStatus.Back : PipeStatus.Next;
    }
}