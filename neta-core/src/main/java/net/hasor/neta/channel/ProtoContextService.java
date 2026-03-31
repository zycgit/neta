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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.Futures;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Default {@link ProtoContext} implementation for one logical channel pipeline.
 * <p>It holds the channel-oriented {@link ProtoStackChain}, the typed attachments exposed through
 * {@link ProtoContext#context(Class)}, per-pass flash data, and the reentrant status stack used
 * while nested receive or send operations execute inside the pipeline.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoContext
 * @see ProtoStackChain
 */
class ProtoContextService implements ProtoBuildContext {
    private final    SoChannel<?>          channel;
    private final    SoContext             soContext;
    private final    Map<Class<?>, Object> contextData;  // local to this ctx; upward lookup via context(Class<T>)
    private final    ProtoStackChain       chainRoot;
    private final    Map<String, Object>   namedHandlerMap;
    private final    ProtoContextService   parentCtx;
    private final    String                parentPrevStackName; // node before Router in parent chain (SND direction); null if Router is head-most
    private final    String                parentNextStackName; // node after Router in parent chain (RCV direction); null if Router is tail-most
    //
    private final    Map<String, Object>   flashMap;
    private final    Deque<ProtoStatus>    statusStack;
    private volatile ProtoStatus           statusCurrent;
    private final    ProtoRecoveryState    recoveryState;

    ProtoContextService(SoChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.contextData = new ConcurrentHashMap<>();
        this.parentCtx = null;
        this.parentPrevStackName = null;
        this.parentNextStackName = null;
        //
        this.flashMap = new HashMap<>();
        this.statusStack = new ArrayDeque<>();
        this.statusStack.push(new ProtoStatus());
        this.statusCurrent = this.statusStack.peek();
        this.recoveryState = new ProtoRecoveryState();
        //
        this.chainRoot = new ProtoStackChain(channel.getConfig());
        this.namedHandlerMap = new HashMap<>();
    }

    /** Create a branch-mode ProtoContextService with independent contextData. */
    ProtoContextService(ProtoContextService parent, int rcvSlotSize, int sndSlotSize, String parentPrevStackName, String parentNextStackName) {
        this.channel = parent.channel;
        this.soContext = parent.soContext;
        this.contextData = new ConcurrentHashMap<>();  // independent per-ctx; upward lookup is automatic via context(Class<T>)
        this.parentCtx = parent;
        this.parentPrevStackName = parentPrevStackName; // pre-computed at branch creation time, never changes
        this.parentNextStackName = parentNextStackName; // pre-computed at branch creation time, never changes
        //
        this.flashMap = parent.flashMap;
        this.statusStack = new ArrayDeque<>();
        this.statusStack.push(new ProtoStatus());
        this.statusCurrent = this.statusStack.peek();
        this.recoveryState = new ProtoRecoveryState();
        //
        this.chainRoot = new ProtoStackChain(rcvSlotSize, sndSlotSize, true);
        this.namedHandlerMap = new HashMap<>();
    }

    ProtoStackChain getChainRoot() {
        return this.chainRoot;
    }

    private ProtoContextService rootRecoveryContext() {
        return this.parentCtx != null ? this.parentCtx.rootRecoveryContext() : this;
    }

    // recovery

    void setupRecovery(String ownerId, String branchName) {
        this.recoveryState.setupSource(ownerId, branchName);
    }

    void registerRecovery(boolean isRcv) {
        this.rootRecoveryContext().recoveryState.registerRecovery(this.recoveryState, isRcv);
    }

    int beginRecovery() {
        return this.rootRecoveryContext().recoveryState.beginRecovery();
    }

    void endRecovery() {
        this.rootRecoveryContext().recoveryState.endRecovery();
    }

    boolean hasRecovery() {
        return this.rootRecoveryContext().recoveryState.hasRecovery();
    }

    boolean hasRecovery(boolean isRcv, String ownerId, String branchName) {
        return this.rootRecoveryContext().recoveryState.hasRecovery(isRcv, ownerId, branchName);
    }

    String activeRecovery(boolean isRcv, String ownerId) {
        return this.rootRecoveryContext().recoveryState.activeRecovery(isRcv, ownerId);
    }
    //

    /** Return the registered handler instance by name; return {@code null} if not found. */
    Object getHandler(String name) {
        return this.namedHandlerMap.get(name);
    }

    /** {@inheritDoc} */
    @Override
    public NetConfig getConfig() {
        return this.soContext.getConfig();
    }

    /** {@inheritDoc} */
    @Override
    public SoChannel<?> getChannel() {
        return this.channel;
    }

    /** {@inheritDoc} */
    @Override
    public SoContext getSoContext() {
        return this.soContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getStackName() {
        return this.statusCurrent.stackName;
    }

    void setStackName(String name) {
        this.statusCurrent.stackName = name;
    }

    /** {@inheritDoc} */
    @Override
    public <T> T context(Class<T> attachment) {
        T val = (T) this.contextData.get(attachment);
        if (val == null && this.parentCtx != null) {
            return this.parentCtx.context(attachment);
        } else {
            return val;
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        this.contextData.put(attachmentType, attachment);
        return attachment;
    }

    /** {@inheritDoc} */
    @Override
    public <T> T rootContext(Class<T> type) {
        return this.parentCtx != null ? this.parentCtx.rootContext(type) : context(type);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T rootContext(Class<T> type, T value) {
        if (this.parentCtx != null) {
            return this.parentCtx.rootContext(type, value);
        } else {
            return this.context(type, value);
        }
    }

    /** Enter a new reentrant pipeline frame. */
    void pushStatus() {
        this.statusStack.push(new ProtoStatus());
        this.statusCurrent = this.statusStack.peek();
    }

    void popStatus() {
        if (this.statusStack.size() > 1) {
            this.statusStack.pop();
        }
    }

    void clearStatus() {
        this.statusStack.peek().clear();
        this.statusCurrent = this.statusStack.peek();
    }

    void clearFlash() {
        this.flashMap.clear();
    }

    // --- Package-private fast internal flash accessors (bypass HashMap) ---

    void beginRcv(Throwable error) {
        this.pushStatus();
        this.statusCurrent.inRcv = true;
        this.statusCurrent.rcvError = error;
        if (this.channel instanceof NetChannel) {
            NetChannel.enterPipeline((NetChannel) this.channel);
        }
    }

    void beginSnd(Throwable error) {
        this.pushStatus();
        this.statusCurrent.inSnd = true;
        this.statusCurrent.sndError = error;
        if (this.channel instanceof NetChannel) {
            NetChannel.enterPipeline((NetChannel) this.channel);
        }
    }

    /** Paired with {@code beginRcv}/{@code beginSnd}: pop the current frame, or just clear it when already on the base frame. */
    void end() {
        this.statusCurrent.clear();
        this.popStatus();
        this.statusCurrent = this.statusStack.peek();
        if (this.channel instanceof NetChannel) {
            NetChannel.exitPipeline((NetChannel) this.channel);
        }
    }

    Throwable getRcvError() {
        return this.statusCurrent.rcvError;
    }

    void setRcvError(Throwable t) {
        this.statusCurrent.rcvError = t;
    }

    Throwable getSndError() {
        return this.statusCurrent.sndError;
    }

    void setSndError(Throwable t) {
        this.statusCurrent.sndError = t;
    }

    /** {@inheritDoc} */
    @Override
    public <T> T flash(String key) {
        return (T) this.flashMap.get(key);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T flash(String key, T flash) {
        if (flash == null) {
            this.flashMap.remove(key);
        } else {
            this.flashMap.put(key, flash);
        }
        return flash;
    }

    /**
     * For branch contexts only. Propagate already encoded data upward through ancestor branches
     * until it reaches the main pipeline. At each level, only handlers between the inner router and
     * that branch's SND-chain head are executed, namely handlers before the owning router in SND
     * direction. The router itself is skipped because the lower branch has already finished encoding.
     */
    private Future<?> sendOrFlushUpward(Object[] encoded) throws Throwable {
        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentPrevStackName != null) {
            ChainResult cr = this.parentCtx.chainRoot.onSnd(this.parentCtx, this.parentPrevStackName, encoded, null);
            encoded = cr.data;
        }

        if (this.parentCtx.parentCtx == null) {
            return netChannel.sendEncoded(encoded);// parent is the main pipeline.
        } else {
            return this.parentCtx.sendOrFlushUpward(encoded);// parent is itself a branch (nested routing).
        }
    }

    /** {@inheritDoc} */
    @Override
    public Future<?> sendData(Object writeData) {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support sendData.");
        }

        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentCtx != null) {
            // in sub pipline, need pop to up parent pipline.
            return this.sendWithinBranch(new Object[] { writeData });
        } else {
            // in main pipline, need pop to up parent pipline.
            String current = this.statusCurrent.safeStackName();
            if (this.isRcv() || this.isSnd()) {
                return this.continueCurrentFrame(netChannel, current, new Object[] { writeData });
            } else {
                return netChannel.sendData(writeData, current);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Future<?> flush() {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support flush.");
        }

        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentCtx != null) {
            // in sub pipline, need pop to up parent pipline.
            return this.sendWithinBranch(null);
        } else {
            // in main pipline, need pop to up parent pipline.
            String current = this.statusCurrent.safeStackName();
            if (this.isRcv() || this.isSnd()) {
                return this.continueCurrentFrame(netChannel, current, null);
            } else {
                return netChannel.flush(current);
            }
        }
    }

    private Future<?> sendWithinBranch(Object[] writeData) {
        try {
            ChainResult cr = this.chainRoot.onSnd(this, null, writeData, null);
            return this.sendOrFlushUpward(cr.data);
        } catch (Throwable e) {
            return Futures.buildFailed(e);
        }
    }

    private Future<?> continueCurrentFrame(NetChannel netChannel, String current, Object[] writeData) {
        Objects.requireNonNull(current, "current stackName must not be null while continuing an active message frame.");
        try {
            ChainResult cr = this.chainRoot.onSnd(this, current, writeData, null);
            return netChannel.sendEncoded(cr.data);
        } catch (Throwable e) {
            return Futures.buildFailed(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> void fireEvent(Class<T> eventType, T event) throws Throwable {
        this.fireEvent0(eventType, event, this.isRcv());
    }

    /** {@inheritDoc} */
    @Override
    public <T> void fireEventReverse(Class<T> eventType, T event) throws Throwable {
        this.fireEvent0(eventType, event, !this.isRcv());
    }

    /** {@inheritDoc} */
    @Override
    public <T> void fireEventRcv(Class<T> eventType, T event) throws Throwable {
        this.fireEvent0(eventType, event, true);
    }

    /** {@inheritDoc} */
    @Override
    public <T> void fireEventSnd(Class<T> eventType, T event) throws Throwable {
        this.fireEvent0(eventType, event, false);
    }

    private <T> void fireEvent0(Class<T> eventType, T event, boolean rcvDirection) throws Throwable {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support fireEvent.");
        }

        String current = this.statusCurrent.stackName;
        current = StringUtils.isBlank(current) ? null : current;

        if (this.parentCtx != null) {
            SoEvent soEvent = SoEventObject.of(this.channel, eventType, event);
            String found = rcvDirection ?//
                    this.chainRoot.findNextStack(current) ://
                    this.chainRoot.findPreviousStack(current);

            if (found != null) {
                if (rcvDirection) {
                    this.chainRoot.onRcvEvent(this, found, soEvent);
                } else {
                    this.chainRoot.onSndEvent(this, found, soEvent);
                }
            } else {
                this.fireEventUpward(rcvDirection, soEvent);
            }
        } else {
            String found = rcvDirection ? this.chainRoot.findNextStack(current) : this.chainRoot.findPreviousStack(current);
            ((NetChannel) this.channel).notifyEvent(rcvDirection, found, eventType, event);
        }
    }

    private void fireEventUpward(boolean isRcv, SoEvent soEvent) throws Throwable {
        if (isRcv) {
            // Cross from end of current branch into parent pipeline after the Router
            if (this.parentNextStackName != null) {
                this.parentCtx.chainRoot.onRcvEvent(this.parentCtx, this.parentNextStackName, soEvent);
            }
        } else {
            // Cross from start of current branch into parent pipeline before the Router
            if (this.parentPrevStackName != null) {
                this.parentCtx.chainRoot.onSndEvent(this.parentCtx, this.parentPrevStackName, soEvent);
            }
        }

        // If the parent is itself a nested branch, continue crossing upward.
        if (this.parentCtx.parentCtx != null) {
            this.parentCtx.fireEventUpward(isRcv, soEvent);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ByteBufAllocator byteBufAllocator() {
        return this.soContext.getByteBufAllocator();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRcv() {
        return this.statusCurrent.inRcv;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSnd() {
        return this.statusCurrent.inSnd;
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirst(SoUtils.generateName(decoder, encoder), new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirst(name, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(duplexer, "duplexer is null.");
        this.addFirst(SoUtils.generateName(duplexer), duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        this.addFirst(name, ProtoConfig.DEFAULT, duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(String name, ProtoConfig protoConf, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(duplexer, "duplexer is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the duplexer name '" + name + "' already exists.");
        }

        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLast(SoUtils.generateName(decoder, encoder), new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLast(name, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(duplexer, "duplexer is null.");
        this.addLast(SoUtils.generateName(duplexer), duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        this.addLast(name, ProtoConfig.DEFAULT, duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(String name, ProtoConfig protoConf, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(duplexer, "duplexer is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the duplexer name '" + name + "' already exists.");
        }

        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, duplexer);
    }

    /** {@inheritDoc} */
    @Override
    public void addLast(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLast(name, protoConf, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addFirst(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirst(name, protoConf, new ProtoDuplexerHandlerWrap<>(decoder, encoder));
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstEncoder(ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addFirstEncoder(SoUtils.generateName(encoder), encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstEncoder(String name, ProtoHandler<?, ?> encoder) {
        this.addFirstEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the encoder or duplexer name '" + name + "' already exists.");
        }

        ProtoEncoderDuplexWrap<Object, ?, ?> duplexer = new ProtoEncoderDuplexWrap<>(encoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastEncoder(ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        this.addLastEncoder(SoUtils.generateName(encoder), encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastEncoder(String name, ProtoHandler<?, ?> encoder) {
        this.addLastEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the encoder or duplexer name '" + name + "' already exists.");
        }

        ProtoEncoderDuplexWrap<Object, ?, ?> duplexer = new ProtoEncoderDuplexWrap<>(encoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, encoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstDecoder(ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        this.addFirstDecoder(SoUtils.generateName(decoder), decoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstDecoder(String name, ProtoHandler<?, ?> decoder) {
        this.addFirstDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addFirstDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(decoder, "decoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the decoder or duplexer name '" + name + "' already exists.");
        }

        ProtoDecoderDuplexWrap<?, ?, Object> duplexer = new ProtoDecoderDuplexWrap<>(decoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.insertProtoStack(invocation);
        this.namedHandlerMap.put(name, decoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastDecoder(ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        this.addLastDecoder(SoUtils.generateName(decoder), decoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastDecoder(String name, ProtoHandler<?, ?> decoder) {
        this.addLastDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /** {@inheritDoc} */
    @Override
    public void addLastDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        Objects.requireNonNull(name, "name is null.");
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(decoder, "decoder is null.");

        if (this.namedHandlerMap.containsKey(name)) {
            throw new UnsupportedOperationException("the decoder or duplexer name '" + name + "' already exists.");
        }

        ProtoDecoderDuplexWrap<?, ?, Object> duplexer = new ProtoDecoderDuplexWrap<>(decoder);
        ProtoInvocation<?, ?, ?, ?> invocation = new ProtoInvocation<>(name, protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), duplexer, this.chainRoot);
        this.chainRoot.appendProtoStack(invocation);
        this.namedHandlerMap.put(name, decoder);
    }

    private static final class ProtoStatus {
        boolean   inRcv     = false;
        boolean   inSnd     = false;
        String    stackName = null;
        Throwable rcvError  = null;
        Throwable sndError  = null;

        void clear() {
            this.inRcv = false;
            this.inSnd = false;
            this.stackName = null;
            this.rcvError = null;
            this.sndError = null;
        }

        String safeStackName() {
            return StringUtils.isBlank(this.stackName) ? null : this.stackName;
        }
    }
}