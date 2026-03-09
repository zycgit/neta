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
 * Thrown when a read-idle timeout condition is reported for a channel.
 * <p>Unlike {@link SoRcvException}, this does not necessarily indicate a transport failure.
 * It is used both by transport receive loops and by explicit wait-for-receive helpers on
 * {@link NetChannel}. Common handling strategies:
 * <ul>
 *   <li><b>Heartbeat / keep-alive</b> – send a probe message and reset the idle timer;
 *       close the channel only if the peer fails to respond within a further grace period.</li>
 *   <li><b>Immediate close</b> – suitable for protocols with strict activity requirements
 *       where any silence indicates a dead peer.</li>
 *   <li><b>Log and ignore</b> – if the protocol allows long idle gaps (e.g., a push stream
 *       with infrequent messages), simply log the event and continue waiting.</li>
 * </ul>
 * <p>The transport-level read timeout is configured via {@link SoConfig#getSoReadTimeoutMs()}.
 * A value of {@code -1} disables that idle check.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoWriteTimeoutException
 * @see SoTimeoutException
 */
public class SoReadTimeoutException extends SoTimeoutException {
    public SoReadTimeoutException(String s) {
        super(s);
    }

    public SoReadTimeoutException(String s, Throwable e) {
        super(s, e);
    }
}