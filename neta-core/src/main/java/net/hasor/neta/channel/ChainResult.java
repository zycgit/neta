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
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Result of a chained {@link ProtoStackChain} execution, carrying data, final status, and any
 * unhandled error left on the chain.
 * <p>It replaces the raw {@code Object[]} return values previously used by methods such as
 * {@link ProtoStackChain#onRcv} and {@link ProtoStackChain#onSnd}, allowing child pipelines
 * (branches) to propagate errors and status back to the parent pipeline.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-06
 */
class ChainResult {
    private static final Object[]   EMPTY_DATA = new ByteBuf[0];
    public static final ChainResult EMPTY      = new ChainResult(EMPTY_DATA, ProtoStatus.Next, null);

    final Object[]    data;
    final ProtoStatus status;
    final Throwable   error;

    public ChainResult(Object[] data, ProtoStatus status, Throwable error) {
        this.data = data != null ? data : EMPTY_DATA;
        this.status = status != null ? status : ProtoStatus.Next;
        this.error = error;
    }
}