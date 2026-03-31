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
import java.io.Closeable;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Inbound data queue at one stage boundary of the protocol pipeline.
 * <p>It stores inbound messages already produced by the current stage. Upstream handlers put
 * messages into the queue, and downstream handlers read them back out.</p>
 * <h3>Consumption methods</h3>
 * <ul>
 *   <li>{@link #takeMessage(int)}: removes messages from the queue, so they no longer remain there.</li>
 *   <li>{@link #peekMessage(int)}: inspects messages without changing the queue contents or order.</li>
 *   <li>{@link #skipMessage(int)}: discards messages, and the queue is responsible for cleanup of discarded objects.</li>
 * </ul>
 * <h3>Ownership</h3>
 * <ul>
 *   <li>While messages remain in the queue, ownership belongs to the queue.</li>
 *   <li>After {@link #takeMessage(int)}, ownership of extracted messages transfers to the caller.</li>
 *   <li>During {@link #peekMessage(int)}, ownership remains with the queue.</li>
 *   <li>During {@link #skipMessage(int)}, discarded messages are released or closed by the queue.</li>
 * </ul>
 * @param <T> received message type
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndQueue
 * @see ProtoQueue
 */
public interface ProtoRcvQueue<T> {
    /**
     * Return the queue capacity.
     * <p>A negative capacity passed during construction is normalized by implementations to an
     * effectively unbounded queue.</p>
     */
    int getCapacity();

    /**
     * Return the current number of readable messages.
     */
    int queueSize();

    /** Return {@code true} when at least one message can be read immediately. */
    default boolean hasMore() {
        return queueSize() > 0;
    }

    /**
     * Remove one message from the queue and transfer ownership to the caller.
     */
    default T takeMessage() {
        List<T> msg = this.takeMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Remove up to {@code cnt} messages from the queue and transfer ownership to the caller.
     * <p>These messages are removed from the queue before return. Once extracted, the queue no
     * longer releases or manages them.</p>
     * @param cnt maximum number of messages to remove; a negative value means all currently readable messages
     */
    List<T> takeMessage(int cnt);

    /**
     * Inspect the most recent message in the queue.
     * <p>Ownership remains with the queue.</p>
     */
    default T peekMessage() {
        List<T> msg = this.peekMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Inspect up to {@code cnt} messages in the queue.
     * <p>The returned result is a copy. Deleting or modifying that list does not affect queue state.</p>
     * <p>Ownership remains with the queue.</p>
     * @param cnt maximum number of messages to inspect; a negative value means all currently readable messages
     */
    List<T> peekMessage(int cnt);

    /**
     * Skip up to {@code cnt} messages still owned by the queue.
     * <p>If a skipped object implements {@link ReferenceHolder}, {@link Release}, or
     * {@link Closeable}, the queue releases it during discard.</p>
     * @param cnt maximum number of messages to discard
     */
    void skipMessage(int cnt);
}