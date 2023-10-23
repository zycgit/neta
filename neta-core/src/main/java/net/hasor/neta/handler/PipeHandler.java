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
package net.hasor.neta.handler;
import net.hasor.neta.channel.PipeContext;

import java.io.IOException;

/**
 * Used to represent a unidirectional data processor, two {@link PipeHandler}`s in opposite directions can to {@link PipeLayer}
 *
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeLayer
 */
@FunctionalInterface
public interface PipeHandler<IN, OUT> {
    /**
     * Initialize the protocol stack
     */
    default void initHandler(PipeContext pipeContext) throws Exception {
    }

    /**
     * release protocol stack
     */
    PipeStatus doHandler(PipeContext context, PipeRcvQueue<IN> src, PipeSndQueue<OUT> dst) throws IOException;

    /** Gets called if a Throwable was thrown. */
    default PipeStatus doError(PipeContext context, PipeRcvQueue<IN> src, PipeSndQueue<OUT> dst, PipeExceptionHandler e) {
        return PipeStatus.Next;
    }

    default void releaseHandler(PipeContext pipeContext) {
    }
}