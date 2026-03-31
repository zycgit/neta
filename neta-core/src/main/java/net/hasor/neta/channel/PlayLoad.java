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
/**
 * Single message event published on the {@link SoChannel} event bus.
 * <p>Every piece of data flowing through the protocol pipeline, regardless of direction, is first
 * wrapped as a {@code PlayLoad} and then dispatched to registered {@link PlayLoadListener}
 * instances. Real network-backed {@link SoChannel} implementations usually expose only inbound
 * data to listeners; outbound data sent to the remote peer normally does not loop back as a local
 * event.</p>
 * <p>For virtual pipelines or in-memory interconnect scenarios, data from both directions may be
 * wrapped and published as {@code PlayLoad} instances.</p>
 * <p>A payload represents either success, carrying decoded or encoded {@code data}, or failure,
 * carrying a pipeline {@code error}, but never both at the same time.</p>
 * <h3>Direction</h3>
 * <ul>
 *   <li>{@link #isInbound()} - data or errors coming from the remote peer (receive direction).</li>
 *   <li>{@link #isOutbound()} - data or errors produced by the local application (send direction).</li>
 * </ul>
 * <p>Instances are created through {@link PlayLoadObject#of} for success or
 * {@link PlayLoadObject#ofError} for failure.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see PlayLoadListener
 * @see SoChannel#subscribe
 */
public interface PlayLoad {
    /**
     * Return the source channel that owns this payload.
     * @return source SoChannel instance
     */
    SoChannel<?> getSource();

    /**
     * Return the data carried by this payload.
     * <p>Objects received through message notifications are still owned by the pipeline, which
     * usually means listeners do not need to release them.</p>
     * <p>If a listener needs to keep using a reference-counted object such as {@code ByteBuf} after
     * the callback returns, it should retain or copy the object inside the callback first.</p>
     * @return data object carried by the payload, or {@code null} when an error occurred
     */
    Object getData();

    /**
     * Return the error associated with this payload, if any.
     * @return the Throwable error object, or {@code null} when the operation succeeded
     */
    Throwable getError();

    /**
     * Return whether this payload represents a successful operation.
     * @return {@code true} when no error is present, otherwise {@code false}
     */
    boolean isSuccess();

    /**
     * Return whether this payload belongs to the inbound, receive direction.
     * @return {@code true} for inbound payloads, otherwise {@code false}
     */
    boolean isInbound();

    /**
     * Return whether this payload belongs to the outbound, send direction.
     * @return {@code true} for outbound payloads, otherwise {@code false}
     */
    boolean isOutbound();
}