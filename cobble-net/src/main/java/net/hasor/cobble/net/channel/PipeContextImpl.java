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
package net.hasor.cobble.net.channel;
/**
 * PipeContext implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeContextImpl implements PipeContext {
    private final NetChannel    channel;
    private final SoContextImpl context;
    private final SoResManager  rm;

    public PipeContextImpl(NetChannel channel, SoContextImpl context, SoResManager rm) {
        this.channel = channel;
        this.context = context;
        this.rm = rm;
    }

    @Override
    public SoConfig getConfig() {
        return null;
    }

    @Override
    public NetChannel channel() {
        return this.channel;
    }

    @Override
    public SoResManager getSoResManager() {
        return this.rm;
    }

    @Override
    public <T> T context(Class<T> attachment) {
        return null;
    }

    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        return null;
    }

    public void clearFlash() {

    }

    @Override
    public <T> T flash(String key) {
        return null;
    }

    @Override
    public <T> T flash(String key, T flash) {
        return null;
    }
}