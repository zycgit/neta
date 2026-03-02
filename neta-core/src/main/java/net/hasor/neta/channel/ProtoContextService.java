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
import java.util.Arrays;
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
    static final  int                   F_IN_RCV     = 0;
    static final  int                   F_IN_SND     = 1;
    static final  int                   F_STACK_NAME = 2;
    static final  int                   F_RCV_ERROR  = 3;
    static final  int                   F_SND_ERROR  = 4;
    static final  int                   F_SIZE       = 5;
    @SuppressWarnings("unchecked")
    private final Map<String, Object>[] _flashMaps;     // user flash per depth level
    //
    private final SoChannel<?>          channel;
    private final SoContext             soContext;
    private final Map<Class<?>, Object> contextData;  // shared across the whole pipeline tree
    private final ProtoChainRoot        chainRoot;
    private final Map<String, Object>   namedHandlerMap;
    // Internal flash: [depth][F_SIZE].  Each push/pop isolates re-entrant call frames.
    Object[][] _flash;
    int        _flashDepth = 0;
    // Branch routing context — set by ProtoRoutingDuplexer.onInit
    private ProtoContextService parentCtx;
    private String              routerStackName;

    ProtoContextService(SoChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.contextData = new ConcurrentHashMap<>();
        this.chainRoot = new ProtoChainRoot(channel.getConfig());
        this.namedHandlerMap = new HashMap<>();
        this._flash = new Object[2][F_SIZE];
        this._flashMaps = new Map[2];
        this._flashMaps[0] = new HashMap<>();
    }

    /** Creates a branch-mode ProtoContextService sharing the root pipeline's contextData. */
    ProtoContextService(ProtoContextService parent, int rcvSlotSize, int sndSlotSize) {
        this.channel = parent.channel;
        this.soContext = parent.soContext;
        this.contextData = parent.contextData;  // same map — global across the whole pipeline tree
        this.chainRoot = new ProtoChainRoot(rcvSlotSize, sndSlotSize, true);
        this.namedHandlerMap = new HashMap<>();
        this._flash = new Object[2][F_SIZE];
        this._flashMaps = new Map[2];
        this._flashMaps[0] = new HashMap<>();
    }

    ProtoChainRoot getChainRoot() {
        return this.chainRoot;
    }

    /** Called by {@link ProtoRoutingDuplexer#onInit} to bind the branch to its parent pipeline. */
    void setParentCtx(ProtoContextService parentCtx) {
        this.parentCtx = parentCtx;
    }

    /** Called by {@link ProtoRoutingDuplexer#onInit} to record the router's stack-name in the parent pipeline. */
    void setRouterStackName(String routerStackName) {
        this.routerStackName = routerStackName;
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
        return (String) this._flash[this._flashDepth][F_STACK_NAME];
    }

    void setStackName(String name) {
        this._flash[this._flashDepth][F_STACK_NAME] = name;
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
        Arrays.fill(this._flash[0], null);
        if (this._flashMaps[0] != null && !this._flashMaps[0].isEmpty()) {
            this._flashMaps[0].clear();
        }
        this._flashDepth = 0; // safety: ensure depth is reset to idle
    }

    /** Enter a new re-entrant pipeline frame. */
    void pushFlash() {
        int next = this._flashDepth + 1;
        if (next >= 2) {
            throw new IllegalStateException("flash depth overflow.");
        }
        this._flashDepth = next;
        Arrays.fill(this._flash[next], null);
        if (this._flashMaps[next] == null) {
            this._flashMaps[next] = new HashMap<>();
        } else {
            this._flashMaps[next].clear();
        }
    }

    // --- Package-private fast internal flash accessors (bypass HashMap) ---

    /** Paired with {@code beginRcv}/{@code beginSnd}: clears the current frame and restores the outer one. */
    void end() {
        int cur = this._flashDepth;
        Arrays.fill(this._flash[cur], null);
        if (this._flashMaps[cur] != null) {
            this._flashMaps[cur].clear();
        }
        this._flashDepth--;
    }

    void beginRcv() {
        this.pushFlash();
        this._flash[this._flashDepth][F_IN_RCV] = Boolean.TRUE;
        this._flash[this._flashDepth][F_IN_SND] = null;
    }

    void beginRcv(Throwable error) {
        this.pushFlash();
        this._flash[this._flashDepth][F_IN_RCV] = Boolean.TRUE;
        this._flash[this._flashDepth][F_IN_SND] = null;
        this._flash[this._flashDepth][F_RCV_ERROR] = error;
    }

    void beginSnd() {
        this.pushFlash();
        this._flash[this._flashDepth][F_IN_RCV] = null;
        this._flash[this._flashDepth][F_IN_SND] = Boolean.TRUE;
    }

    void beginSnd(Throwable error) {
        this.pushFlash();
        this._flash[this._flashDepth][F_IN_RCV] = null;
        this._flash[this._flashDepth][F_IN_SND] = Boolean.TRUE;
        this._flash[this._flashDepth][F_SND_ERROR] = error;
    }

    /** Switch direction within the current frame (no push/pop). Used by UserEvent RCV→SND handoff. */
    void switchToSnd() {
        this._flash[this._flashDepth][F_IN_RCV] = null;
        this._flash[this._flashDepth][F_IN_SND] = Boolean.TRUE;
    }

    Throwable getRcvError() {
        return (Throwable) this._flash[this._flashDepth][F_RCV_ERROR];
    }

    void setRcvError(Throwable t) {
        this._flash[this._flashDepth][F_RCV_ERROR] = t;
    }

    Throwable getSndError() {
        return (Throwable) this._flash[this._flashDepth][F_SND_ERROR];
    }

    void setSndError(Throwable t) {
        this._flash[this._flashDepth][F_SND_ERROR] = t;
    }

    @Override
    public <T> T flash(String key) {
        Map<String, Object> m = this._flashMaps[this._flashDepth];
        return m != null ? (T) m.get(key) : null;
    }

    @Override
    public <T> T flash(String key, T flash) {
        Map<String, Object> m = this._flashMaps[this._flashDepth];
        if (m == null) {
            m = new HashMap<>();
            this._flashMaps[this._flashDepth] = m;
        }
        if (flash == null) {
            m.remove(key);
        } else {
            m.put(key, flash);
        }
        return flash;
    }

    /** Walk up the {@code parentCtx} chain and return the {@link #routerStackName} that belongs to the outermost (main) pipeline. */
    private String topLevelRouterName() {
        ProtoContextService ctx = this;
        while (ctx.parentCtx != null && ctx.parentCtx.parentCtx != null) {
            ctx = ctx.parentCtx;
        }
        return ctx.routerStackName;
    }

    @Override
    public Future<?> sendData(Object writeData) {
        if (this.channel instanceof NetChannel) {
            NetChannel netChannel = (NetChannel) this.channel;
            if (this.parentCtx != null && this.routerStackName != null) {
                // Branch ctx: send starting from the Router in the outermost (main) pipeline.
                // The Router's doSndRoute will dispatch the data through all nested branch
                // SND encoders in the correct order, then continue through the main pipeline.
                return netChannel.sendData(writeData, topLevelRouterName());
            } else {
                // Main ctx: start from the current handler position.
                String current = (String) this._flash[this._flashDepth][F_STACK_NAME];
                if (StringUtils.isNotBlank(current)) {
                    return netChannel.sendData(writeData, current);
                } else {
                    return netChannel.sendData(writeData);
                }
            }
        } else {
            throw new UnsupportedOperationException("only NetChannel support sendData.");
        }
    }

    @Override
    public <T> void fireUserEvent(Class<T> eventType, T event) {
        if (this.channel instanceof NetChannel) {
            String current = (String) this._flash[this._flashDepth][F_STACK_NAME];
            current = StringUtils.isBlank(current) ? null : current;

            if (this.parentCtx != null && this.routerStackName != null) {
                // Branch ctx: propagate within branch chain first; when the chain boundary is
                // reached, cross into the main pipeline (after Router for RCV, before Router for SND).
                SoUserEvent soEvent = SoUserEventObject.of(this.channel, eventType, event);
                if (this.isRcv()) {
                    String found = this.chainRoot.findNextStack(current);
                    if (found != null) {
                        // Deliver to next handler within the branch
                        try {
                            this.chainRoot.onRcvUserEvent(this, found, soEvent);
                        } catch (Throwable e) { /* non-fatal */ }
                    } else {
                        // End of branch chain — cross upward through the parent-ctx chain recursively.
                        this.fireUserEventUpward(true, soEvent);
                    }
                } else {
                    String found = this.chainRoot.findPreviousStack(current);
                    if (found != null) {
                        // Deliver to previous handler within the branch
                        try {
                            this.chainRoot.onSndUserEvent(this, found, soEvent);
                        } catch (Throwable e) { /* non-fatal */ }
                    } else {
                        // Start of branch chain — cross upward through the parent-ctx chain recursively.
                        this.fireUserEventUpward(false, soEvent);
                    }
                }
            } else {
                // Main ctx: original logic
                if (this.isRcv()) {
                    String found = this.chainRoot.findNextStack(current);
                    ((NetChannel) this.channel).notifyUserEvent(true, found, eventType, event);
                } else {
                    String found = this.chainRoot.findPreviousStack(current);
                    ((NetChannel) this.channel).notifyUserEvent(false, found, eventType, event);
                }
            }
        } else {
            throw new UnsupportedOperationException("only NetChannel support fireUserEvent.");
        }
    }

    /**
     * Recursively cross the parent-ctx boundary and propagate a user-event upward through
     * nested branch levels until the top of the chain is reached.
     */
    private void fireUserEventUpward(boolean isRcv, SoUserEvent soEvent) {
        if (isRcv) {
            // Cross from end of current branch into parent pipeline after the Router
            String afterRouter = this.parentCtx.chainRoot.findNextStack(this.routerStackName);
            if (afterRouter != null) {
                try {
                    this.parentCtx.chainRoot.onRcvUserEvent(this.parentCtx, afterRouter, soEvent);
                } catch (Throwable e) { /* non-fatal */ }
            }
        } else {
            // Cross from start of current branch into parent pipeline before the Router
            String prevRouter = this.parentCtx.chainRoot.findPreviousStack(this.routerStackName);
            if (prevRouter != null) {
                try {
                    this.parentCtx.chainRoot.onSndUserEvent(this.parentCtx, prevRouter, soEvent);
                } catch (Throwable e) { /* non-fatal */ }
            }
        }
        // If the parent is itself a nested branch, continue crossing upward.
        // (If parentCtx.parentCtx == null the parent is the main pipeline; onRcv/SndUserEvent
        // above already delivered to all remaining main handlers — no further action needed.)
        if (this.parentCtx.parentCtx != null) {
            this.parentCtx.fireUserEventUpward(isRcv, soEvent);
        }
    }

    @Override
    public Future<?> flush() {
        if (this.channel instanceof NetChannel) {
            NetChannel netChannel = (NetChannel) this.channel;
            if (this.parentCtx != null && this.routerStackName != null) {
                // Branch ctx: flush starting from the Router in the outermost (main) pipeline.
                // Router.doSndRoute sees empty sndUp and returns Next immediately, so the flush
                // propagates through all remaining main-pipeline encoders to the wire.
                return netChannel.flush(topLevelRouterName());
            } else {
                String current = (String) this._flash[this._flashDepth][F_STACK_NAME];
                return netChannel.flush(current);
            }
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
        return this._flash[this._flashDepth][F_IN_RCV] != null;
    }

    @Override
    public boolean isSnd() {
        return this._flash[this._flashDepth][F_IN_SND] != null;
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