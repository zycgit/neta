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
 * Typed event signal propagated through a {@link SoChannel} pipeline.
 * <p>User events are out-of-band signals separate from normal data messages. While
 * data flows as raw bytes ({@link ByteBuf}) or decoded objects, user events communicate
 * protocol-level state changes — for example:
 * <ul>
 *   <li>TLS handshake completion ({@code SslHandshakeEvent})</li>
 *   <li>TLS close-notify received ({@code SslCloseNotifyEvent})</li>
 *   <li>SCTP association state change ({@code SctpNotificationEvent})</li>
 * </ul>
 * <p>Fire a user event by calling {@link ProtoContext#fireUserEvent}; it is then dispatched
 * along the current pipeline direction, so the same API works for both receive-side and
 * send-side propagation. The payload is carried by a
 * {@link SoUserEventData} implementation; this interface is the envelope wrapping it
 * together with the source channel reference.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see SoUserEventData
 * @see SoUserEventObject
 */
public interface SoUserEvent {
    /** Returns the channel that fired this event. */
    SoChannel<?> getSource();

    /** Returns the runtime type of the event payload. */
    Class<?> getEventType();

    /** Returns the event payload object. */
    Object getData();
}