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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Default {@link ProtoContext} implementation bound to a single channel.
 * <p>Manages the handler chain ({@link ProtoChainRoot}), per-event flash storage,
 * context attachments, and pipeline manipulation (add/remove handlers).</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class ProtoContextService implements ProtoContext {
    private static final Logger                logger     = Logger.getLogger(ProtoContextService.class);
    private final        SoChannel<?>          channel;
    private final        SoContext             soContext;
    private final        Map<Class<?>, Object> contextData;  // local to this ctx; upward lookup via context(Class<T>)
    private final        ProtoChainRoot        chainRoot;
    private final        Map<String, Object>   namedHandlerMap;
    private final        ProtoContextService   parentCtx;
    private final        String                parentPrevStackName; // node before Router in parent chain (SND direction); null if Router is head-most
    private final        String                parentNextStackName; // node after Router in parent chain (RCV direction); null if Router is tail-most
    //
    private final        Deque<FlashFrame>     flashStack = new ArrayDeque<>();
    private volatile     FlashFrame            flashCurrent;

    ProtoContextService(SoChannel<?> channel, SoContext soContext) {
        this.channel = channel;
        this.soContext = soContext;
        this.contextData = new ConcurrentHashMap<>();
        this.parentCtx = null;
        this.parentPrevStackName = null;
        this.parentNextStackName = null;
        //
        this.chainRoot = new ProtoChainRoot(channel.getConfig());
        this.namedHandlerMap = new HashMap<>();
        this.flashStack.push(new FlashFrame());
        this.flashCurrent = this.flashStack.peek();
    }

    /** Creates a branch-mode ProtoContextService with its own independent contextData. */
    ProtoContextService(ProtoContextService parent, int rcvSlotSize, int sndSlotSize, String parentPrevStackName, String parentNextStackName) {
        this.channel = parent.channel;
        this.soContext = parent.soContext;
        this.contextData = new ConcurrentHashMap<>();  // independent per-ctx; upward lookup is automatic via context(Class<T>)
        this.parentCtx = parent;
        this.parentPrevStackName = parentPrevStackName; // pre-computed at branch creation time, never changes
        this.parentNextStackName = parentNextStackName; // pre-computed at branch creation time, never changes
        //
        this.chainRoot = new ProtoChainRoot(rcvSlotSize, sndSlotSize, true);
        this.namedHandlerMap = new HashMap<>();
        this.flashStack.push(new FlashFrame());
        this.flashCurrent = this.flashStack.peek();
    }

    ProtoChainRoot getChainRoot() {
        return this.chainRoot;
    }

    /** Returns the registered handler instance by name and type, or {@code null} if not found. */
    <T> T getHandler(String name, Class<T> type) {
        Object handler = this.namedHandlerMap.get(name);
        return type.isInstance(handler) ? type.cast(handler) : null;
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
        return this.flashCurrent.stackName;
    }

    void setStackName(String name) {
        this.flashCurrent.stackName = name;
    }

    @Override
    public <T> T context(Class<T> attachment) {
        T val = (T) this.contextData.get(attachment);
        if (val == null && this.parentCtx != null) {
            return this.parentCtx.context(attachment);
        }
        return val;
    }

    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        this.contextData.put(attachmentType, attachment);
        return attachment;
    }

    @Override
    public <T> T rootContext(Class<T> type) {
        return this.parentCtx != null ? this.parentCtx.rootContext(type) : context(type);
    }

    @Override
    public <T> T rootContext(Class<T> type, T value) {
        if (this.parentCtx != null) {
            return this.parentCtx.rootContext(type, value);
        }
        return context(type, value);
    }

    void clearFlash() {
        while (this.flashStack.size() > 1) {
            this.flashStack.pop();
        }
        this.flashStack.peek().clear();
        this.flashCurrent = this.flashStack.peek();
    }

    /** Enter a new re-entrant pipeline frame. */
    void pushFlash() {
        FlashFrame frame = new FlashFrame();
        this.flashStack.push(frame);
        this.flashCurrent = frame;
    }

    /** Paired with {@code beginRcv}/{@code beginSnd}: pops the current frame; if already at base, just clears it. */
    void end() {
        this.flashCurrent.clear();
        if (this.flashStack.size() > 1) {
            this.flashStack.pop();
        }
        this.flashCurrent = this.flashStack.peek();
    }

    // --- Package-private fast internal flash accessors (bypass HashMap) ---

    void beginRcv() {
        this.pushFlash();
        this.flashCurrent.inRcv = true;
    }

    void beginRcv(Throwable error) {
        this.pushFlash();
        this.flashCurrent.inRcv = true;
        this.flashCurrent.rcvError = error;
    }

    void beginSnd() {
        this.pushFlash();
        this.flashCurrent.inSnd = true;
    }

    void beginSnd(Throwable error) {
        this.pushFlash();
        this.flashCurrent.inSnd = true;
        this.flashCurrent.sndError = error;
    }

    Throwable getRcvError() {
        return this.flashCurrent.rcvError;
    }

    void setRcvError(Throwable t) {
        this.flashCurrent.rcvError = t;
    }

    Throwable getSndError() {
        return this.flashCurrent.sndError;
    }

    void setSndError(Throwable t) {
        this.flashCurrent.sndError = t;
    }

    @Override
    public <T> T flash(String key) {
        Map<String, Object> m = this.flashCurrent.flashMap;
        return m != null ? (T) m.get(key) : null;
    }

    @Override
    public <T> T flash(String key, T flash) {
        Map<String, Object> m = this.flashCurrent.flashMap;
        if (m == null) {
            m = new HashMap<>();
            this.flashCurrent.flashMap = m;
        }
        if (flash == null) {
            m.remove(key);
        } else {
            m.put(key, flash);
        }
        return flash;
    }

    /**
     * Branch ctx only. Propagates already-encoded data upward through ancestor branches until
     * the main pipeline is reached. At each level, only the handlers that lie between the
     * inner Router and the head of that branch's SND chain are executed (i.e. the handlers
     * BEFORE the Router that owns this branch, in SND direction). The Router itself is skipped
     * since the data is already encoded by the branch below.
     */
    private Future<?> sendOrFlushUpward(Object[] encoded) throws Throwable {
        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentPrevStackName != null) {
            encoded = this.parentCtx.chainRoot.onSndMessage(this.parentCtx, this.parentPrevStackName, encoded);
        }

        if (this.parentCtx.parentCtx == null) {
            return netChannel.sendEncoded(encoded);// parent is the main pipeline.
        } else {
            return this.parentCtx.sendOrFlushUpward(encoded);// parent is itself a branch (nested routing).
        }
    }

    @Override
    public Future<?> sendData(Object writeData) {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support sendData.");
        }

        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentCtx != null) {
            // in sub pipeline
            try {
                Object[] encoded = this.chainRoot.onSndMessage(this, null, new Object[] { writeData });
                return sendOrFlushUpward(encoded);
            } catch (Throwable e) {
                return Futures.buildFailed(e);
            }
        } else {
            // main ctx: start from the current handler position.
            String current = this.flashCurrent.stackName;
            if (StringUtils.isNotBlank(current)) {
                return netChannel.sendData(writeData, current);
            } else {
                return netChannel.sendData(writeData);
            }
        }
    }

    @Override
    public <T> void fireUserEvent(Class<T> eventType, T event) throws Throwable {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support fireUserEvent.");
        }

        String current = this.flashCurrent.stackName;
        current = StringUtils.isBlank(current) ? null : current;

        if (this.parentCtx != null) {
            // Branch ctx: propagate within branch chain first; when the chain boundary is
            // reached, cross into the main pipeline (after Router for RCV, before Router for SND).
            SoUserEvent soEvent = SoUserEventObject.of(this.channel, eventType, event);
            if (this.isRcv()) {
                String found = this.chainRoot.findNextStack(current);
                if (found != null) {
                    // Deliver to next handler within the branch
                    this.chainRoot.onRcvUserEvent(this, found, soEvent);
                } else {
                    // End of branch chain — cross upward through the parent-ctx chain recursively.
                    this.fireUserEventUpward(true, soEvent);
                }
            } else {
                String found = this.chainRoot.findPreviousStack(current);
                if (found != null) {
                    // Deliver to previous handler within the branch
                    this.chainRoot.onSndUserEvent(this, found, soEvent);
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
    }

    private void fireUserEventUpward(boolean isRcv, SoUserEvent soEvent) throws Throwable {
        if (isRcv) {
            // Cross from end of current branch into parent pipeline after the Router
            if (this.parentNextStackName != null) {
                this.parentCtx.chainRoot.onRcvUserEvent(this.parentCtx, this.parentNextStackName, soEvent);
            }
        } else {
            // Cross from start of current branch into parent pipeline before the Router
            if (this.parentPrevStackName != null) {
                this.parentCtx.chainRoot.onSndUserEvent(this.parentCtx, this.parentPrevStackName, soEvent);
            }
        }

        // If the parent is itself a nested branch, continue crossing upward.
        if (this.parentCtx.parentCtx != null) {
            this.parentCtx.fireUserEventUpward(isRcv, soEvent);
        }
    }

    @Override
    public Future<?> flush() {
        if (!(this.channel instanceof NetChannel)) {
            throw new UnsupportedOperationException("only NetChannel support flush.");
        }

        NetChannel netChannel = (NetChannel) this.channel;
        if (this.parentCtx != null) {
            try {
                Object[] encoded = this.chainRoot.onSndMessage(this, null, new Object[0]);
                return sendOrFlushUpward(encoded);
            } catch (Throwable e) {
                return Futures.buildFailed(e);
            }
        } else {
            return netChannel.flush(this.flashCurrent.stackName);
        }
    }

    @Override
    public ByteBufAllocator byteBufAllocator() {
        return this.soContext.getByteBufAllocator();
    }

    @Override
    public boolean isRcv() {
        return this.flashCurrent.inRcv;
    }

    @Override
    public boolean isSnd() {
        return this.flashCurrent.inSnd;
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

    private static final class FlashFrame {
        boolean             inRcv     = false;
        boolean             inSnd     = false;
        String              stackName = null;
        Throwable           rcvError  = null;
        Throwable           sndError  = null;
        Map<String, Object> flashMap  = null;

        void clear() {
            this.inRcv = false;
            this.inSnd = false;
            this.stackName = null;
            this.rcvError = null;
            this.sndError = null;
            if (this.flashMap != null) {
                this.flashMap.clear();
            }
        }
    }
}