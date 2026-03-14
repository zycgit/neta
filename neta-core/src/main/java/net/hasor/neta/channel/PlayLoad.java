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
 * A single message event published on the {@link SoChannel} event bus.
 * <p>Every data item travelling through the protocol pipeline — in either direction — is
 * wrapped in a {@code PlayLoad} before being dispatched to registered
 * {@link PlayLoadListener}s.  A payload is either successful (carrying decoded/encoded
 * {@code data}) or erroneous (carrying a pipeline {@code error}), never both.
 * <h3>Direction</h3>
 * <ul>
 *   <li>{@link #isInbound()} – the data or error arrived from the remote peer (received).</li>
 *   <li>{@link #isOutbound()} – the data or error was produced by the local application (sent).</li>
 * </ul>
 * <p>Construct instances via {@link PlayLoadObject#of} (success) or
 * {@link PlayLoadObject#ofError} (failure).
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see PlayLoadListener
 * @see SoChannel#subscribe
 */
public interface PlayLoad {
    /**
     * Gets the source channel from which this payload originated.
     * @return the source SoChannel instance
     */
    SoChannel<?> getSource();

    /**
     * Gets the data contained in this payload.
     * <p>The returned object is usually still owned by the pipeline/transport that emitted this
     * event. Listeners that need to use reference-counted values such as {@code ByteBuf} after the
     * callback returns should retain or copy them inside the callback.
     * @return the payload data object, or null if an error occurred
     */
    Object getData();

    /**
     * Gets the error associated with this payload, if any.
     * @return the Throwable error, or null if the operation was successful
     */
    Throwable getError();

    /**
     * Indicates whether the payload represents a successful operation.
     * @return true if no error is present, false otherwise
     */
    boolean isSuccess();

    /**
     * Indicates whether the payload is inbound (received).
     * @return true if the payload is inbound, false otherwise
     */
    boolean isInbound();

    /**
     * Indicates whether the payload is outbound (sent).
     * @return true if the payload is outbound, false otherwise
     */
    boolean isOutbound();
}