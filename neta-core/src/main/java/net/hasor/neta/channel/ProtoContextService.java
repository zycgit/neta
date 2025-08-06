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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBufAllocator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ProtoContext} implements
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class ProtoContextService implements ProtoContext {
    private final NetDuplexChannel<?>   channel;
    private final SoContext             soContext;
    private final Map<Class<?>, Object> contextData;
    private final Map<String, Object>   flash;

    protected ProtoContextService(NetDuplexChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.contextData = new HashMap<>();
        this.flash = new HashMap<>();
    }

    @Override
    public SoConfig getConfig() {
        return this.soContext.getConfig();
    }

    @Override
    public NetDuplexChannel<?> getChannel() {
        return this.channel;
    }

    @Override
    public SoContext getSoContext() {
        return this.soContext;
    }

    @Override
    public <T> T context(Class<T> attachment) {
        return (T) this.contextData.get(attachment);
    }

    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        this.contextData.put(attachmentType, attachment);
        return attachment;
    }

    public void clearFlash() {
        this.flash.clear();
    }

    @Override
    public <T> T flash(String key) {
        return (T) this.flash.get(key);
    }

    @Override
    public <T> T flash(String key, T flash) {
        if (flash == null) {
            this.flash.remove(key);
        } else {
            this.flash.put(key, flash);
        }
        return flash;
    }

    @Override
    public Future<?> sendData(Object writeData) throws IOException {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                return ((NetChannel) this.channel).sendData(writeData, current);
            } else {
                return ((NetChannel) this.channel).sendData(writeData);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Future<?> flush() throws IOException {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            return ((NetChannel) this.channel).flush(current);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public ByteBufAllocator byteBufAllocator() {
        return this.soContext.getByteBufAllocator();
    }

    @Override
    public boolean isRcv() {
        Object flash = this.flash(ProtoContext.CURRENT_PROTO_IN_RCV);
        return flash != null && (boolean) flash;
    }

    @Override
    public boolean isSnd() {
        Object flash = this.flash(ProtoContext.CURRENT_PROTO_IN_SND);
        return flash != null && (boolean) flash;
    }
}