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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Protocol-pipeline context for one channel and its branch sub-pipelines.
 * <p>A connection has one root {@code ProtoContext} attached to the public channel and may create
 * additional branch contexts under routing nodes. The API serves two roles: pipeline construction
 * during initialization and runtime interaction while handlers are processing data or user events.
 * <p><b>Structure:</b>
 * <pre>
 *   NetChannel / QuicStreamChannel
 *       -> root ProtoContext
 *       -> branch ProtoContext(s) created by ProtoRoutingDuplexer
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface ProtoContext {
    /** global config */
    NetConfig getConfig();

    /** the channel */
    SoChannel<?> getChannel();

    /** the SoContext */
    SoContext getSoContext();

    /**
     * Returns the name of the current protocol stack name.
     * <p><b>Note:</b> This method will only return a valid result when called within the processing flow
     * of {@link ProtoDuplexer} or {@link ProtoHandler}.</p>
     * @return the name of the current protocol stack
     */
    String getStackName();

    /**
     * Retrieves an attachment from this ctx's local store by type, searching upward through
     * parent ctx instances if not found at the current level (<em>proximate-first</em> lookup).
     * <p>
     * Each ctx in the pipeline tree has its own independent store.  Writes via
     * {@link #context(Class, Object)} go to the current ctx only; reads propagate up through
     * parent ctxs (branch → parent branch → root) until a match is found, or {@code null}
     * is returned if no ctx in the ancestry has stored a value for that type.
     * </p>
     * <p>To access or write directly to the root (connection-level) store use
     * {@link #rootContext(Class)} and {@link #rootContext(Class, Object)}.</p>
     * @param attachment the type key
     * @return the nearest stored value in this ctx's ancestor chain, or {@code null} if none
     */
    <T> T context(Class<T> attachment);

    /**
     * Stores an attachment in <b>this ctx's local store</b> by type.
     * <p>
     * The value is written only to the current ctx and is immediately visible to
     * {@link #context(Class)} calls made from this ctx or any descendant ctx via upward
     * traversal.  It is <em>not</em> automatically visible in sibling or parent ctxs.
     * </p>
     * <p>To write directly to the root (connection-level) store use
     * {@link #rootContext(Class, Object)}.</p>
     * @param attachmentType the type key
     * @param attachment the value to store; {@code null} is allowed
     * @return the stored value
     */
    <T> T context(Class<T> attachmentType, T attachment);

    /**
     * Retrieves an attachment from the <b>root (connection-level) ctx</b> by type.
     * <p>
     * The root ctx is the outermost main-pipeline ctx created for the connection.
     * Values stored here are shared across the entire pipeline tree and persist for
     * the connection's lifetime.  Use this when you need true connection-global state
     * that is readable from any branch regardless of nesting depth.
     * </p>
     * @param type the type key
     * @return the stored value, or {@code null} if nothing has been stored under that type
     */
    <T> T rootContext(Class<T> type);

    /**
     * Stores an attachment in the <b>root (connection-level) ctx</b> by type.
     * <p>
     * The value is written to the outermost main-pipeline ctx and is therefore
     * visible to every handler in every branch via {@link #context(Class)} upward
     * traversal (provided no inner ctx has shadowed the key) for the rest of the
     * connection's lifetime.
     * </p>
     * @param type the type key
     * @param value the value to store; {@code null} is allowed
     * @return the stored value
     */
    <T> T rootContext(Class<T> type, T value);

    /**
     * Retrieves a flash value by key from the active event frame.
     * <p>
     * Flash storage is ephemeral per event pass, but in the current implementation it is shared by
     * the root ctx and any branch ctxs participating in that same pass. Values are automatically
     * discarded when the outermost event processing completes.
     * </p>
     * @param key the flash key
     * @return the stored value, or {@code null} if not present in the current frame
     */
    <T> T flash(String key);

    /**
     * Stores a flash value in the active event frame.
     * <p>
     * The value is visible to the root ctx and branch ctxs participating in the same active pass,
     * but it is still short-lived: once that outermost pass ends the flash map is cleared.
     * For state that must survive beyond one event, use {@link #context(Class, Object)}.
     * </p>
     * @param key the flash key
     * @param flash the value to store; passing {@code null} removes the key
     * @return the stored value
     */
    <T> T flash(String key, T flash);

    /**
     * Send data through the SND pipeline, starting from the <b>current handler's position</b>
     * and traveling <em>backward</em> (tail → head, the encoding direction).
     * <h3>Main pipeline</h3>
     * The data is passed to the handler immediately before the caller in the SND chain,
     * then continues toward the head and finally reaches the network.
     * <pre>
     *   [A] ◀── [B*] ◀── [C]
     *            │
     *         sendData() starts here; data flows left: B → A → wire
     * </pre>
     * <h3>Branch pipeline (bottom-up encoding)</h3>
     * In a branch context the data is encoded <em>bottom-up</em>, one layer at a time:
     * <ol>
     *   <li>Run the current branch's full SND chain (all handlers from tail to head).</li>
     *   <li>Pass the encoded result to the parent pipeline, running only the handlers
     *       that lie <em>before</em> our Router in the parent's SND chain.</li>
     *   <li>Repeat step 2 for every ancestor branch until the outermost pipeline is reached.</li>
     *   <li>Submit the final bytes directly to the network task queue, skipping the Router
     *       entirely to prevent double-encoding.</li>
     * </ol>
     * <pre>
     *   Main:   [A] ◀── [Router] ◀── [Z]
     *                      │
     *           Branch: [B] ◀── [C*]
     *   Path (bottom-up):
     *     1. branch SND chain: C → B  (encoded)
     *     2. main segment before Router: A  (if present)
     *     3. wire  (Router skipped)
     * </pre>
     * @param writeData the application-level object to send; must be compatible with the
     * first SND handler's expected input type
     * @return a {@link Future} that completes when the encoded bytes have been handed off
     * to the network task queue; failure is reported via {@link Future#getCause()}
     */
    Future<?> sendData(Object writeData);

    /**
     * Fire a typed user event that propagates <b>along the current data-flow direction</b>,
     * crossing branch boundaries if necessary until the head/tail of the outermost pipeline
     * is reached.
     * <p>When called from inside a handler, propagation resumes from the <em>next</em> handler in
     * the current direction rather than re-entering the caller itself.</p>
     * <h3>Propagation direction</h3>
     * <ul>
     *   <li>In a <b>RCV</b> context ({@link #isRcv()} == true): events travel
     *       <em>forward</em> (head → tail, same direction as decoded data).</li>
     *   <li>In a <b>SND</b> context ({@link #isRcv()} == false): events travel
     *       <em>backward</em> (tail → head, same direction as encoded data).</li>
     * </ul>
     * <h3>Branch boundary crossing</h3>
     * When the event reaches the end of the current branch pipeline (no more handlers
     * in the propagation direction), it automatically <em>crosses the branch boundary</em>
     * and enters the parent pipeline — entering after the Router (RCV) or before the
     * Router (SND) — and continues propagating from that point.
     * <p>Nested branches are traversed recursively until the outermost pipeline is reached.</p>
     * <h3>Example — RCV direction (handler C fires event)</h3>
     * <pre>
     *  Main: [A] ──▶ [Router] ──▶ [Z]
     *                   │
     *         Branch: [B] ──▶ [C*] · · ·?· · ·▶ (boundary crossed) ──▶ [Z]
     *  Path: (after C) → (end of branch) → Z → ...
     * </pre>
     * <h3>Example — SND direction (handler B fires event)</h3>
     * <pre>
     *  Main: [A] ◀── [Router] ◀── [Z]
     *                   │
     *         Branch: [B*] ◀── [C]
     *                  ·
     *                  · (boundary crossed)
     *                  ·
     *                 [A] ◀── ...
     *  Path: (before B) → (start of branch) → A → ...
     * </pre>
     * @param eventType the runtime type token used to route the event to interested handlers
     * @param event the event payload
     */
    <T> void fireUserEvent(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed user event in the <b>opposite direction of the current data-flow</b>,
     * starting from the next handler in that reverse direction and
     * crossing branch boundaries upward when necessary.
     * <p>
     * This is useful when a downstream protocol needs to request a state change from an earlier
     * protocol layer that sits before it in the pipeline, such as a websocket handshake asking
     * the preceding HTTP codec to switch transport mode.
     * </p>
     * <ul>
     *   <li>In a <b>RCV</b> context, the event travels backward (tail → head).</li>
     *   <li>In a <b>SND</b> context, the event travels forward (head → tail).</li>
     * </ul>
     * <h3>Branch boundary crossing</h3>
     * When the reverse-direction walk reaches the edge of the current branch pipeline,
     * the event does not stop inside the branch. Instead it <em>crosses upward</em> into
     * the parent pipeline and continues from the router boundary:
     * <ul>
     *   <li><b>RCV context</b>: crosses to the handler <em>before</em> the Router in the parent pipeline.</li>
     *   <li><b>SND context</b>: crosses to the handler <em>after</em> the Router in the parent pipeline.</li>
     * </ul>
     * <p>Nested branches are traversed recursively until the outermost pipeline is reached.</p>
     * <h3>Example — RCV context (handler C requests an upstream change)</h3>
     * <pre>
     *  Main: [A] ──▶ [Router] ──▶ [Z]
     *                   │
     *         Branch: [B] ──▶ [C*]
     *  fireUserEventReverse path: (before C) → B → (cross boundary) → A → ...
     * </pre>
     * <h3>Example — SND context (handler B requests an upstream change)</h3>
     * <pre>
     *  Main: [A] ◀── [Router] ◀── [Z]
     *                   │
     *         Branch: [B*] ◀── [C]
     *  fireUserEventReverse path: (after B) → C → (cross boundary) → Z → ...
     * </pre>
     * @param eventType the runtime type token used to route the event to interested handlers
     * @param event the event payload
     */
    <T> void fireUserEventReverse(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed user event explicitly in the <b>RCV direction</b> (head → tail),
     * regardless of whether the current callback is running in RCV or SND mode.
     * <p>
     * In a branch pipeline, when the event reaches the branch tail it automatically crosses
     * into the parent pipeline after the Router and continues in RCV direction.
     * </p>
     * @param eventType the runtime type token used to route the event to interested handlers
     * @param event the event payload
     */
    <T> void fireUserEventRcv(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed user event explicitly in the <b>SND direction</b> (tail → head),
     * regardless of whether the current callback is running in RCV or SND mode.
     * <p>
     * In a branch pipeline, when the event reaches the branch head it automatically crosses
     * into the parent pipeline before the Router and continues in SND direction.
     * </p>
     * @param eventType the runtime type token used to route the event to interested handlers
     * @param event the event payload
     */
    <T> void fireUserEventSnd(Class<T> eventType, T event) throws Throwable;

    /**
     * Flush the SND pipeline without sending new data, giving every SND handler an opportunity
     * to drain internal write buffers (e.g. compressors, chunked encoders).
     * <p>
     * The propagation rules are identical to {@link #sendData}: the flush signal travels
     * <em>backward</em> (tail → head) starting from the current handler's position, and in a
     * branch context the bottom-up encoding path is followed so that each layer's handlers
     * flush exactly once and the Router is never re-entered.
     * </p>
     * @return a {@link Future} that completes when the flush has been submitted to the
     * network task queue; failure is reported via {@link Future#getCause()} not null.
     */
    Future<?> flush();

    /** return ByteBufAllocator from SoContext. */
    ByteBufAllocator byteBufAllocator();

    /** Returns the pipeline mode is receive */
    boolean isRcv();

    /** Returns the pipeline mode is sent */
    boolean isSnd();

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer);

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name duplexer name
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer);

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name duplexer name
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>RCV_UP equal to RCV_DOWN</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstEncoder(ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstEncoder(String name, ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>RCV_UP equal to RCV_DOWN</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastEncoder(ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastEncoder(String name, ProtoHandler<?, ?> encoder);

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN equal to SND_UP</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstDecoder(ProtoHandler<?, ?> decoder);

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstDecoder(String name, ProtoHandler<?, ?> decoder);

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN equal to SND_UP</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastDecoder(ProtoHandler<?, ?> decoder);

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastDecoder(String name, ProtoHandler<?, ?> decoder);
}