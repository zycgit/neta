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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * {@link ProtoContext} implements
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class ProtoContextService implements ProtoContext {
    private final SoChannel<?>          channel;
    private final SoContext             soContext;
    private final Map<Class<?>, Object> contextData;
    private final ProtoChainRoot        chainRoot;
    private final Map<String, Object>   flash;
    private final Map<String, Object>   namedHandlerMap;

    ProtoContextService(SoChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.contextData = new ConcurrentHashMap<>();
        this.chainRoot = new ProtoChainRoot(channel.getConfig());
        this.flash = new HashMap<>();
        this.namedHandlerMap = new HashMap<>();
    }

    /**
     * Creates a branch-mode ProtoContext that shares contextData and flash with the parent.
     * Each branch has its own namedHandlerMap since branches have independent pipeline chains.
     */
    ProtoContextService(ProtoContextService parent, int rcvSlotSize, int sndSlotSize) {
        this.channel = parent.channel;
        this.soContext = parent.soContext;
        this.contextData = parent.contextData;
        this.chainRoot = new ProtoChainRoot(rcvSlotSize, sndSlotSize, true);
        this.flash = parent.flash;
        this.namedHandlerMap = new HashMap<>();
    }

    ProtoChainRoot getChainRoot() {
        return this.chainRoot;
    }

    @Override
    public NetConfig getConfig() {
        return this.soContext.getConfig();
    }

    @Override
    public SoChannel<?> getChannel() {
        return this.channel;
    }

    @Override
    public SoContext getSoContext() {
        return this.soContext;
    }

    @Override
    public String getStackName() {
        return this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
    }

    @Override
    public String findNextStack(String withName) {
        return this.chainRoot.findNextStack(withName);
    }

    @Override
    public String findPreviousStack(String withName) {
        return this.chainRoot.findPreviousStack(withName);
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

    void clearFlash() {
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
            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                return ((NetChannel) this.channel).sendData(writeData, current);
            } else {
                return ((NetChannel) this.channel).sendData(writeData);
            }
        } else {
            throw new UnsupportedOperationException("only NetChannel support sendData.");
        }
    }

    @Override
    public <T> void fireUserEvent(Class<T> eventType, T event) {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            current = StringUtils.isBlank(current) ? null : current;

            if (this.isRcv()) {
                String found = this.chainRoot.findNextStack(current);
                ((NetChannel) this.channel).notifyUserEvent(true, found, eventType, event);
            } else {
                String found = this.chainRoot.findPreviousStack(current);
                ((NetChannel) this.channel).notifyUserEvent(false, found, eventType, event);
            }
        } else {
            throw new UnsupportedOperationException("only NetChannel support fireUserEvent.");
        }
    }

    @Override
    public Future<?> flush() {
        if (this.channel instanceof NetChannel) {
            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            return ((NetChannel) this.channel).flush(current);
        } else {
            throw new UnsupportedOperationException("only NetChannel support flush.");
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

    @Override
    public void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirst(SoUtils.generateName(decoder, encoder), new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    @Override
    public void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirst(name, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    @Override
    public void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(duplexer, "duplexer is null.");
        this.addFirst(SoUtils.generateName(duplexer), duplexer);
    }

    @Override
    public void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(duplexer, "duplexer is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the duplexer name '" + name + "' already exists.");
        }

        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, duplexer);
    }

    @Override
    public void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLast(SoUtils.generateName(decoder, encoder), new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    @Override
    public void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLast(name, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    @Override
    public void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(duplexer, "duplexer is null.");
        this.addLast(SoUtils.generateName(duplexer), duplexer);
    }

    @Override
    public void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(duplexer, "duplexer is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the duplexer name '" + name + "' already exists.");
        }

        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, duplexer);
    }

    @Override
    public void addFirstEncoder(ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirstEncoder(SoUtils.generateName(encoder), encoder);
    }

    @Override
    public void addFirstEncoder(String name, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the encoder or duplexer name '" + name + "' already exists.");
        }

        ProtoEncoderDuplexWrap<Object, ?, ?> duplexer = new ProtoEncoderDuplexWrap<>(encoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, encoder);
    }

    @Override
    public void addLastEncoder(ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLastEncoder(SoUtils.generateName(encoder), encoder);
    }

    @Override
    public void addLastEncoder(String name, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the encoder or duplexer name '" + name + "' already exists.");
        }

        ProtoEncoderDuplexWrap<Object, ?, ?> duplexer = new ProtoEncoderDuplexWrap<>(encoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, encoder);
    }

    @Override
    public void addFirstDecoder(ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        this.addFirstDecoder(SoUtils.generateName(decoder), decoder);
    }

    @Override
    public void addFirstDecoder(String name, ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(decoder, "decoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the decoder or duplexer name '" + name + "' already exists.");
        }

        ProtoDecoderDuplexWrap<?, ?, Object> duplexer = new ProtoDecoderDuplexWrap<>(decoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, decoder);
    }

    @Override
    public void addLastDecoder(ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        this.addLastDecoder(SoUtils.generateName(decoder), decoder);
    }

    @Override
    public void addLastDecoder(String name, ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(decoder, "decoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the decoder or duplexer name '" + name + "' already exists.");
        }

        ProtoDecoderDuplexWrap<?, ?, Object> duplexer = new ProtoDecoderDuplexWrap<>(decoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, -1, -1, duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, decoder);
    }
}