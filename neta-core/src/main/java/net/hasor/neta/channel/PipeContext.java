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
import net.hasor.cobble.concurrent.future.Future;

import java.io.IOException;

/**
 * Each {@link NetChannel} has a {@link PipeContext}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface PipeContext {

    String CURRENT_PIPE_STACK_NAME  = PipeContext.class.getName() + "_CURRENT_PIPE_STACK_NAME";
    String CURRENT_PIPE_STACK_DEPTH = PipeContext.class.getName() + "_CURRENT_PIPE_STACK_DEPTH";
    String SO_CHANNEL_RETRY_CNT     = PipeContext.class.getName() + "SO_CHANNEL_RETRY_CNT";

    /** global config */
    SoConfig getConfig();

    /** the channel */
    SoChannel<?> channel();

    /** the SoContext */
    SoContext getSoContext();

    /** Get the attachment for {@link PipeContext} */
    <T> T context(Class<T> attachment);

    /** receive buffer full */
    default boolean rcvFull() {
        Object retryCnt = flash(SO_CHANNEL_RETRY_CNT);
        if (retryCnt == null) {
            return false;
        } else {
            return (int) retryCnt > 0;
        }
    }

    /** bind attachment to {@link PipeContext} */
    <T> T context(Class<T> attachmentType, T attachment);

    /** Get flash for current Pipe event. */
    <T> T flash(String key);

    /**
     * set flash to current {@link PipeContext}, the flash event.
     * <p>flash has a very short lifetime and will be automatically cleaned up after a full PipeLayer protocol stack is processed</p>
     * if you need long-term storage Use {@link #context(Class, Object)}
     */
    <T> T flash(String key, T flash);

    /** write data to protocol stack, the event propagates backward from the current protocol layer */
    Future<?> sendData(Object writeData) throws IOException;

    /** Refresh the protocol stack, the event propagates backward from the current protocol layer */
    Future<?> flush() throws IOException;
}