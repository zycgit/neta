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
 * Thrown when a connection attempt has not completed within the configured deadline.
 * <p>Unlike {@link SoConnectException} (where the remote host actively refused the connection),
 * this exception indicates the host was reachable at the network layer but did not respond
 * in time — a common symptom when a firewall silently drops SYN packets rather than sending
 * a TCP RST.
 * <p>When this exception is thrown the channel is automatically closed. To retry, create a
 * new channel and call connect again. Adjusting the connect-timeout value in the channel's
 * {@code SoConfig} may help on high-latency links.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoConnectException
 * @see SoTimeoutException
 */
public class SoConnectTimeoutException extends SoTimeoutException {
    public SoConnectTimeoutException(String s) {
        super(s);
    }

    public SoConnectTimeoutException(String s, Throwable e) {
        super(s, e);
    }
}