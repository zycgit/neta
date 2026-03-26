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
import java.util.List;

/**
 * Inbound (receive-side) data queue for one endpoint of a protocol pipeline stage.
 * <p>Each pipeline stage boundary has exactly one {@code ProtoRcvQueue}: the upstream
 * producer (e.g. a decoder) offers decoded messages into it; the downstream consumer
 * reads them out.
 * <p>This interface no longer models a transactional queue. There is no submit/reset
 * phase: successful writes are immediately visible, and destructive reads/discards take
 * effect immediately.
 * <h3>Ownership semantics</h3>
 * <ol>
 *   <li>Call {@link #peekMessage(int)} for non-destructive look-ahead.</li>
 *   <li>Call {@link #takeMessage(int)} to destructively transfer message ownership to the caller.</li>
 *   <li>Call {@link #skipMessage(int)} to destructively discard queue-owned messages without returning them.</li>
 * </ol>
 * @param <T> the type of received message
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndQueue
 * @see ProtoQueue
 */
public interface ProtoRcvQueue<T> {
    /**
     * Returns queue capacity.
     * <p>A negative constructor capacity is normalized by the implementation to an
     * effectively unbounded queue.</p>
     */
    int getCapacity();

    /**
     * Returns the number of currently readable messages.
     */
    int queueSize();

    /** Returns {@code true} when at least one message is immediately readable. */
    default boolean hasMore() {
        return queueSize() > 0;
    }

    /**
     * Destructively takes one message from the queue and transfers ownership to the caller.
     */
    default T takeMessage() {
        List<T> msg = this.takeMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Destructively takes up to {@code cnt} messages from the queue and transfers ownership
     * to the caller.
     * <p>The returned messages are removed from the queue before the call returns. Once taken,
     * the queue will no longer release or manage them.</p>
     * @param cnt the maximum number of messages to take; negative means take all currently readable messages
     */
    List<T> takeMessage(int cnt);

    /**
     * Non-destructively peeks one message from the queue.
     * <p>Ownership remains with the queue.</p>
     */
    default T peekMessage() {
        List<T> msg = this.peekMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Non-destructively peeks up to {@code cnt} messages from the queue.
     * <p>The returned view is a copy. Removing or mutating that list does not affect queue state,
     * and ownership of the queued objects remains with the queue until they are taken or skipped.</p>
     * @param cnt the maximum number of messages to peek; negative means peek all currently readable messages
     */
    List<T> peekMessage(int cnt);

    /**
     * Destructively skips up to {@code cnt} messages that remain queue-owned.
     * <p>If a skipped object implements {@link net.hasor.neta.bytebuf.ReferenceHolder}, the queue
     * is responsible for releasing it as part of the discard operation. Implementations may also
     * close skipped objects that implement {@link java.io.Closeable}.</p>
     * <p>This method is for discard only. It must not be used to emulate ownership transfer after
     * {@link #peekMessage(int)}.</p>
     * @param cnt the maximum number of messages to discard; negative values are treated as implementation-defined bulk discard
     */
    void skipMessage(int cnt);
}