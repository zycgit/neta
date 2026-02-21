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
    // Internal flash indices — replaces HashMap for hot-path internal keys
    static final  int                   F_IN_RCV        = 0;
    static final  int                   F_IN_SND        = 1;
    static final  int                   F_STACK_NAME    = 2;
    static final  int                   F_RCV_ERROR     = 3;
    static final  int                   F_SND_ERROR     = 4;
    static final  int                   F_SKIP_SND_LIFE = 5;
    static final  int                   F_SIZE          = 6;
    final         Object[]              _flash; // internal flash array (shared in branch mode)
    //
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
        this._flash = new Object[F_SIZE];
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
        this._flash = parent._flash; // shared with parent
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
        return (String) this._flash[F_STACK_NAME];
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
        Object[] f = this._flash;
        f[0] = null;
        f[1] = null;
        f[2] = null;
        f[3] = null;
        f[4] = null;
        f[5] = null;
        if (!this.flash.isEmpty()) {
            this.flash.clear();
        }
    }

    // --- Package-private fast internal flash accessors (bypass HashMap) ---

    void setRcvMode() {
        this._flash[F_IN_RCV] = Boolean.TRUE;
        this._flash[F_IN_SND] = null;
    }

    void setSndMode() {
        this._flash[F_IN_RCV] = null;
        this._flash[F_IN_SND] = Boolean.TRUE;
    }

    void setStackName(String name) {
        this._flash[F_STACK_NAME] = name;
    }

    Throwable getRcvError() {
        return (Throwable) this._flash[F_RCV_ERROR];
    }

    void setRcvError(Throwable t) {
        this._flash[F_RCV_ERROR] = t;
    }

    Throwable getSndError() {
        return (Throwable) this._flash[F_SND_ERROR];
    }

    void setSndError(Throwable t) {
        this._flash[F_SND_ERROR] = t;
    }

    boolean isSkipSndLife() {
        return this._flash[F_SKIP_SND_LIFE] != null;
    }

    void setSkipSndLife() {
        this._flash[F_SKIP_SND_LIFE] = Boolean.TRUE;
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
            String current = (String) this._flash[F_STACK_NAME];
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
            String current = (String) this._flash[F_STACK_NAME];
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
            String current = (String) this._flash[F_STACK_NAME];
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
        return this._flash[F_IN_RCV] != null;
    }

    @Override
    public boolean isSnd() {
        return this._flash[F_IN_SND] != null;
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