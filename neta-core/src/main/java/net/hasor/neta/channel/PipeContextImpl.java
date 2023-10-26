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
import java.util.HashMap;
import java.util.Map;

/**
 * PipeContext implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class PipeContextImpl implements PipeContext {
    private final SoChannel<?>          channel;
    private final SoContext             soContext;
    private final SoResManager          rm;
    private final Map<Class<?>, Object> pipeContext;
    private final Map<String, Object>   flash;

    protected PipeContextImpl(SoChannel<?> channel, SoContext soContext, SoResManager rm) {
        this.channel = channel;
        this.soContext = soContext;
        this.rm = rm;
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
    public SoResManager getSoResManager() {
        return this.rm;
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
}