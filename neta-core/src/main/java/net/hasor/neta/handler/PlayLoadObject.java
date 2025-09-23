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
import net.hasor.neta.channel.SoChannel;

/**
 * A message data in the message bus
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
class PlayLoadObject implements PlayLoad {
    private final boolean      success;
    private final Object       data;
    private final Throwable    error;
    private final SoChannel<?> source;
    private final boolean      inbound;
    private final boolean      outbound;

    PlayLoadObject(Object data, Throwable error, SoChannel<?> source, boolean inbound, boolean outbound) {
        this.success = error == null;
        this.data = data;
        this.error = error;
        this.source = source;
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Override
    public SoChannel<?> getSource() {
        return this.source;
    }

    @Override
    public Object getData() {
        return this.data;
    }

    @Override
    public Throwable getError() {
        return this.error;
    }

    @Override
    public boolean isSuccess() {
        return this.success;
    }

    @Override
    public boolean isInbound() {
        return this.inbound;
    }

    @Override
    public boolean isOutbound() {
        return this.outbound;
    }
}