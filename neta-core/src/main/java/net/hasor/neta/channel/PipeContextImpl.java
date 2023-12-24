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

import java.util.HashMap;
import java.util.Map;

/**
 * PipeContext implements
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class PipeContextImpl implements PipeContext {
    private final SoChannel<?>          channel;
    private final SoContext             soContext;
    private final Map<Class<?>, Object> pipeContext;
    private final Map<String, Object>   flash;

    protected PipeContextImpl(SoChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.pipeContext = new HashMap<>();
        this.flash = new HashMap<>();
    }

    @Override
    public SoConfig getConfig() {
        return this.soContext.getConfig();
    }

    @Override
    public SoChannel<?> channel() {
        return this.channel;
    }

    @Override
    public SoContext getSoContext() {
        return this.soContext;
    }

    @Override
    public <T> T context(Class<T> attachment) {
        return (T) this.pipeContext.get(attachment);
    }

    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        this.pipeContext.put(attachmentType, attachment);
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
    public Future<?> sendData(Object writeData) {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
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
    public Future<?> flush() {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                return ((NetChannel) this.channel).sendData(current);
            } else {
                return ((NetChannel) this.channel).flush();
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }
}