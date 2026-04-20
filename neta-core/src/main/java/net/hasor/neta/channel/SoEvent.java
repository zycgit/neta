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
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Typed event object propagated through a {@link SoChannel} pipeline.
 * <p>Network events are side-band signals independent from normal data messages. Regular data
 * usually flows as raw bytes ({@link ByteBuf}) or decoded objects, while events represent
 * protocol-level state changes such as:</p>
 * <ul>
 *   <li>TLS handshake completion ({@code SslHandshakeEvent})</li>
 *   <li>Receiving TLS close-notify ({@code SslCloseNotifyEvent})</li>
 *   <li>SCTP association state changes ({@code SctpNotificationEvent})</li>
 * </ul>
 * <p>Call {@link ProtoContext#fireEvent} to trigger a network event. The event then propagates
 * along the current pipeline direction, so the same API is used for both inbound and outbound
 * propagation. The actual payload is carried by a {@link SoEventData} implementation, while this
 * interface acts as a wrapper combining the payload with the source channel reference.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see SoEventData
 * @see SoEventObject
 */
public interface SoEvent {
    /** Return the channel that triggered this event. */
    SoChannel<?> getSource();

    /** Return the runtime type of the event payload. */
    Class<?> getEventType();

    /** Return the event payload object. */
    Object getData();
}