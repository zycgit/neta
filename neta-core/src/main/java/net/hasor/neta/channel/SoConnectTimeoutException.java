/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when a connection attempt does not complete before the configured deadline.
 * <p>Unlike {@link SoConnectException}, which indicates that the remote host actively refused the
 * connection, this exception means the host may still be reachable at the network level but did
 * not respond within the allotted time. This commonly happens when a firewall silently drops SYN
 * packets instead of returning a TCP RST.</p>
 * <p>When this exception is thrown, the channel is closed automatically. To retry, create a new
 * channel and call connect again. On high-latency links, increasing the connect timeout in
 * {@code SoConfig} may help.</p>
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
