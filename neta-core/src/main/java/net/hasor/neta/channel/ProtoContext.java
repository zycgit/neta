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
 * Each {@link NetChannel} has a {@link ProtoContext}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface ProtoContext {
    String CURRENT_PROTO_IN_RCV      = ProtoContext.class.getName() + "_CURRENT_PROTO_IN_RCV";
    String CURRENT_PROTO_IN_SND      = ProtoContext.class.getName() + "_CURRENT_PROTO_IN_SND";
    String CURRENT_PROTO_STACK_NAME  = ProtoContext.class.getName() + "_CURRENT_PROTO_STACK_NAME";
    String CURRENT_PROTO_STACK_DEPTH = ProtoContext.class.getName() + "_CURRENT_PROTO_STACK_DEPTH";

    /** global config */
    SoConfig getConfig();

    /** the channel */
    NetDuplexChannel<?> getChannel();

    /** the SoContext */
    SoContext getSoContext();

    /** Get the attachment for {@link ProtoContext} */
    <T> T context(Class<T> attachment);

    /** bind attachment to {@link ProtoContext} */
    <T> T context(Class<T> attachmentType, T attachment);

    /** Get flash for current ProtoStack event. */
    <T> T flash(String key);

    /**
     * set flash to current {@link ProtoContext}, the flash event.
     * <p>flash has a very short lifetime and will be automatically cleaned up, After the event propagation is complete</p>
     * if you need long-term storage Use {@link #context(Class, Object)}
     */
    <T> T flash(String key, T flash);

    /** write data to protocol stack, the event propagates backward from the current protocol layer */
    Future<?> sendData(Object writeData) throws IOException;

    /** Refresh the protocol stack, the event propagates backward from the current protocol layer */
    Future<?> flush() throws IOException;

    /** Returns the pipeline mode is receive */
    boolean isRcv();

    /** Returns the pipeline mode is sent */
    boolean isSnd();
}