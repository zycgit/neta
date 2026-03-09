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
 * Thrown when an outbound connection attempt to a remote address fails or is refused.
 * <p>Common causes:
 * <ul>
 *   <li><b>Connection refused</b> – no process is listening on the target port (ECONNREFUSED).
 *       Indicates the server is down or the port is wrong.</li>
 *   <li><b>Network unreachable / host unreachable</b> – the OS routing table has no route to
 *       the destination, or the link layer reports the host as down.</li>
 *   <li><b>Firewall RST</b> – an intermediate device actively rejected the SYN packet.</li>
 * </ul>
 * <p>When this exception is thrown the channel was never established and can be discarded.
 * To retry the connection, create a new channel via the same {@link NetManager} — a single
 * channel instance cannot be reconnected after a failure.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoConnectTimeoutException
 * @see SoException
 */
public class SoConnectException extends SoException {
    public SoConnectException(String s) {
        super(s);
    }

    public SoConnectException(String s, Throwable e) {
        super(s, e);
    }
}