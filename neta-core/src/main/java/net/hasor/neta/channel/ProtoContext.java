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
import net.hasor.neta.bytebuf.ByteBufAllocator;
/**
 * Protocol runtime context for a single channel and its branch sub-pipelines.
 * <p>A connection has one root {@code ProtoContext} attached to the public channel, and may also
 * create additional branch contexts under routing nodes. Runtime handlers use this API to access
 * context state, propagate events, and perform downstream send or flush operations.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface ProtoContext {
    /** Return the global configuration. */
    NetConfig getConfig();

    /** Return the owning channel. */
    SoChannel<?> getChannel();

    /** Return the SoContext. */
    SoContext getSoContext();

    /**
     * Return the name of the current protocol stack node.
     * <p><b>Note:</b> this method only returns a meaningful result when called from inside the
     * processing flow of a {@link ProtoDuplexer} or {@link ProtoHandler}.</p>
     * @return current protocol stack name
     */
    String getStackName();

    /**
     * Look up an attachment by type from the local storage of the current context; if it is not
     * found locally, parent contexts are searched upward with nearest scope first.
     * <p>Each context in the pipeline tree has its own storage. Data written through
     * {@link #context(Class, Object)} is stored only in the current context. Reads walk upward
     * through the parent chain, branch to parent branch to root, until a match is found. If no
     * ancestor stores a value for the requested type, {@code null} is returned.</p>
     * <p>Use {@link #rootContext(Class)} and {@link #rootContext(Class, Object)} to read or write
     * directly to root, connection-level storage.</p>
     * @param attachment type key
     * @return nearest stored value visible from the current context, or {@code null} if none exists
     */
    <T> T context(Class<T> attachment);

    /**
     * Store an attachment by type in the <b>local storage of the current context</b>.
     * <p>The value is written only to the current context and is immediately visible to that
     * context and any of its child contexts when they search upward through {@link #context(Class)}.
     * It is <em>not</em> automatically visible to sibling or parent contexts.</p>
     * <p>Use {@link #rootContext(Class, Object)} to write directly to root, connection-level storage.</p>
     * @param attachmentType type key
     * @param attachment value to store; {@code null} is allowed
     * @return stored value
     */
    <T> T context(Class<T> attachmentType, T attachment);

    /**
     * Look up an attachment by type from the <b>root, connection-level, context</b>.
     * <p>The root context is the outermost main-pipeline context created for the connection. Values
     * stored there are shared across the whole pipeline tree and remain for the lifetime of the
     * connection. Use this method when you need truly connection-level global state that should be
     * readable from any branch regardless of nesting depth.</p>
     * @param type type key
     * @return stored value, or {@code null} if nothing has been stored under that type yet
     */
    <T> T rootContext(Class<T> type);

    /**
     * Store an attachment by type into the <b>root, connection-level, context</b>.
     * <p>The value is written into the outermost main-pipeline context, so for the rest of the
     * connection lifetime every handler in every branch can see it through upward lookup with
     * {@link #context(Class)}, as long as no inner context shadows the same key.</p>
     * @param type type key
     * @param value value to store; {@code null} is allowed
     * @return stored value
     */
    <T> T rootContext(Class<T> type, T value);

    /**
     * Look up a flash value by key from the current active event frame.
     * <p>Flash storage is short-lived for each event propagation. In the current implementation,
     * the root context and branch contexts participating in the same propagation round share it.
     * These values are discarded automatically when the outermost event handling completes.</p>
     * @param key flash key
     * @return stored value, or {@code null} if it is absent in the current frame
     */
    <T> T flash(String key);

    /**
     * Store a flash value into the current active event frame.
     * <p>The value is visible to the root context and branch contexts participating in the same
     * active propagation round, but it is still short-lived: once the outermost round finishes,
     * the flash map is cleared. If state needs to survive beyond one event, use
     * {@link #context(Class, Object)} instead.</p>
     * @param key flash key
     * @param flash value to store; passing {@code null} removes the key
     * @return stored value
     */
    <T> T flash(String key, T flash);

    /**
     * Send data downstream in the SND direction from the <b>current handler position</b>,
     * propagating in the <em>reverse</em> direction (tail → head, which is the encoding path).
     * <h3>Main pipeline</h3>
     * The data is first delivered to the handler immediately before the caller in the SND chain,
     * then continues flowing toward the head until it reaches the network layer.
     * <pre>
     *   [A] ◀── [B*] ◀── [C]
     *            │
     *         sendData() starts here; data flows left: B → A → wire
     * </pre>
     * <h3>Branch pipeline</h3>
     * In a branch context, the data first traverses the current branch SND chain, then returns to
     * the parent pipeline and continues outward through the main-pipeline segment before the current
     * routing duplexer.
     * <pre>
     *   Main:   [A] ◀── [Router] ◀── [Z]
     *                      │
     *             Branch: [B] ◀── [C*]
     *   sendData() path: C → B → A → wire
     * </pre>
     * <h3>Partition pipeline</h3>
     * In a partition context, the data first traverses the current partition-instance SND chain,
     * then returns to the parent pipeline and continues outward through the main-pipeline segment
     * before the current partition duplexer.
     * <pre>
     *   Main:      [A] ◀── [Partition] ◀── [Z]
     *                          │
     *                  Part-1: [B] ◀── [C*]
     *   sendData() path: C → B → A → wire
     * </pre>
     * @param writeData application-level object to send; it must be compatible with the input type
     * expected by the first SND handler
     * <p>If the returned {@link Future} fails because an SND handler throws during this send
     * attempt, that failure describes only this attempt. As long as the channel remains open,
     * outbound messages still buffered in internal queues across the pipeline are retained, so
     * later send or recovery flows may still continue processing them. These queued messages are
     * automatically released only when the channel-close path runs.</p>
     * @return a {@link Future} that completes when the encoded bytes have been handed off to the
     * network task queue; if it fails, the cause can be retrieved through {@link Future#getCause()}
     */
    Future<?> sendData(Object writeData);

    /**
     * Send data that has already been encoded by the <b>current handler</b> and should therefore
     * continue from the <b>previous SND handler</b> instead of re-entering the current one.
     * <p>This is primarily intended for callbacks such as {@code onActive}, {@code onEvent}, and
     * {@code onError}, where the current handler may need to emit its own downstream object type
     * directly. Typical examples include protocol control frames, locally generated acknowledgements,
     * or error replies already expressed in the current layer's outbound type.</p>
     * <h3>Main pipeline</h3>
     * The data starts from the handler immediately before the caller in SND direction; if the
     * caller is already the head-most SND handler, the data goes directly to the network layer.
     * <pre>
     *   [A] ◀── [B*] ◀── [C]
     *            │
     *      sendEncoded() path: A → wire
     * </pre>
     * <h3>Branch pipeline</h3>
     * In a branch context, the data first continues through any remaining branch-local SND
     * handlers before the caller. After the branch boundary is reached, propagation resumes in the
     * parent pipeline segment before the current routing node.
     * <pre>
     *   Main:   [A] ◀── [Router] ◀── [Z]
     *                      │
     *             Branch: [B] ◀── [C*]
     *   sendEncoded() path: B → A → wire
     * </pre>
     * @param encodedData outbound object already encoded for the current handler's downstream type
     * @return a {@link Future} that completes when the encoded bytes have been handed off to the
     * network task queue; on failure, the cause is available from {@link Future#getCause()}
     */
    Future<?> sendEncoded(Object encodedData);

    /**
     * Batch variant of {@link #sendEncoded(Object)}.
     * @param encodedData outbound objects already encoded for the current handler's downstream type
     * @return a {@link Future} that completes when the encoded bytes have been handed off to the
     * network task queue; on failure, the cause is available from {@link Future#getCause()}
     */
    Future<?> sendEncoded(Object[] encodedData);

    /**
     * Fire a typed network event along the current data-flow direction in the current pipeline,
     * starting from the <b>next handler after the current handler position</b>.
     * <h3>Main pipeline</h3>
     * On the main pipeline, events in an RCV context propagate head → tail, while events in an
     * SND context propagate tail → head.
     * <pre>
     *   RCV: [A] ──▶ [B*] ──▶ [C]   fireEvent() path: B → C
     *   SND: [A] ◀── [B*] ◀── [C]   fireEvent() path: B → A
     * </pre>
     * <h3>Branch pipeline</h3>
     * In a branch context, the event first continues through the current branch. After the branch
     * reaches its boundary, propagation resumes in the parent pipeline segment after the current
     * routing duplexer for RCV, or before it for SND.
     * <pre>
     *   RCV: Main [A] ──▶ [Router] ──▶ [Z]
     *                         │
     *               Branch   [B] ──▶ [C*]
     *        fireEvent() path: C → Z
     *   SND: Main [A] ◀── [Router] ◀── [Z]
     *                         │
     *               Branch   [B*] ◀── [C]
     *        fireEvent() path: B → A
     * </pre>
     * <h3>Partition pipeline</h3>
     * In a partition context, the event first continues through the current partition sub-pipeline.
     * After the sub-pipeline reaches its boundary, propagation resumes in the parent pipeline
     * segment after the current partition duplexer for RCV, or before it for SND.
     * <pre>
     *   RCV: Main [A] ──▶ [Partition] ──▶ [Z]
     *                            │
     *                    Part-1  [B] ──▶ [C*]
     *        fireEvent() path: C → Z
     *   SND: Main [A] ◀── [Partition] ◀── [Z]
     *                            │
     *                    Part-1  [B*] ◀── [C]
     *        fireEvent() path: B → A
     * </pre>
     * @param eventType runtime type token used to route the event to interested handlers
     * @param event event payload
     */
    <T> void fireEvent(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed network event along the direction opposite to the current data flow in the
     * current pipeline, starting from the <b>next handler in the reverse direction</b>.
     * <p>This is useful when the current protocol layer needs to request a state change from the
     * previous or next protocol layer, for example to make an upstream codec switch modes.</p>
     * <h3>Main pipeline</h3>
     * On the main pipeline, events in an RCV context propagate tail → head, while events in an
     * SND context propagate head → tail.
     * <pre>
     *   RCV: [A] ──▶ [B*] ──▶ [C]   fireEventReverse() path: B → A
     *   SND: [A] ◀── [B*] ◀── [C]   fireEventReverse() path: B → C
     * </pre>
     * <h3>Branch pipeline</h3>
     * In a branch context, the event first propagates in reverse through the current branch. After
     * the branch reaches its boundary, propagation resumes in the parent pipeline segment before
     * the current routing duplexer for RCV, or after it for SND.
     * <pre>
     *   RCV: Main [A] ──▶ [Router] ──▶ [Z]
     *                         │
     *               Branch   [B] ──▶ [C*]
     *        fireEventReverse() path: C → B → A
     *   SND: Main [A] ◀── [Router] ◀── [Z]
     *                         │
     *               Branch   [B*] ◀── [C]
     *        fireEventReverse() path: B → C → Z
     * </pre>
     * <h3>Partition pipeline</h3>
     * In a partition context, the event first propagates in reverse through the current partition
     * sub-pipeline. After the sub-pipeline reaches its boundary, propagation resumes in the parent
     * pipeline segment before the current partition duplexer for RCV, or after it for SND.
     * <pre>
     *   RCV: Main [A] ──▶ [Partition] ──▶ [Z]
     *                            │
     *                    Part-1  [B] ──▶ [C*]
     *        fireEventReverse() path: C → B → A
     *   SND: Main [A] ◀── [Partition] ◀── [Z]
     *                            │
     *                    Part-1  [B*] ◀── [C]
     *        fireEventReverse() path: B → C → Z
     * </pre>
     * @param eventType runtime type token used to route the event to interested handlers
     * @param event event payload
     */
    <T> void fireEventReverse(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed network event explicitly in the <b>RCV direction</b> (head → tail), regardless
     * of whether the current callback is running in RCV or SND mode.
     * <p>For the propagation path, see the RCV-direction behavior described in
     * {@link #fireEvent(Class, Object)}. If you want propagation to follow the current direction
     * automatically, use {@link #fireEvent(Class, Object)}. If you want propagation to follow the
     * reverse of the current direction, use {@link #fireEventReverse(Class, Object)}.</p>
     * @param eventType runtime type token used to route the event to interested handlers
     * @param event event payload
     */
    <T> void fireEventRcv(Class<T> eventType, T event) throws Throwable;

    /**
     * Fire a typed network event explicitly in the <b>SND direction</b> (tail → head), regardless
     * of whether the current callback is running in RCV or SND mode.
     * <p>For the propagation path, see the SND-direction behavior described in
     * {@link #fireEvent(Class, Object)}. If you want propagation to follow the current direction
     * automatically, use {@link #fireEvent(Class, Object)}. If you want propagation to follow the
     * reverse of the current direction, use {@link #fireEventReverse(Class, Object)}.</p>
     * @param eventType runtime type token used to route the event to interested handlers
     * @param event event payload
     */
    <T> void fireEventSnd(Class<T> eventType, T event) throws Throwable;

    /**
     * Flush the SND pipeline without sending new data, giving each SND handler a chance to drain
     * its internal write buffer, such as a compressor or chunked encoder.
     * <p>The propagation rules are the same as {@link #sendData}: the flush signal starts from the
     * current handler position and propagates in the <em>reverse</em> direction (tail → head). In
     * branch contexts it follows the bottom-up encoding path, so each handler is flushed only once
     * and the Router is not re-entered.</p>
     * @return a {@link Future} that completes when the flush has been submitted to the network task
     * queue; on failure, the cause is available from a non-null {@link Future#getCause()}
     */
    Future<?> flush();

    /** Return the ByteBufAllocator provided by SoContext. */
    ByteBufAllocator byteBufAllocator();

    /** Return whether the current pipeline mode is receive. */
    boolean isRcv();

    /** Return whether the current pipeline mode is send. */
    boolean isSnd();
}