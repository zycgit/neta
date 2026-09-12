/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when a channel hits its read-idle timeout condition.
 * <p>Unlike {@link SoRcvException}, this does not necessarily mean the transport has failed. It is
 * used both by the low-level receive loop and by explicit wait-for-receive helper methods on
 * {@link NetChannel}. Common handling strategies include:</p>
 * <ul>
 *   <li><b>Heartbeat / keepalive</b>: send a probe message and reset the idle timer, closing the channel only if the peer still does not respond within an additional grace period.</li>
 *   <li><b>Immediate close</b>: suitable for protocols with strict liveness requirements where any silence may be treated as peer death.</li>
 *   <li><b>Log and ignore</b>: for protocols that allow long idle periods, such as low-frequency push streams.</li>
 * </ul>
 * <p>Transport-layer read timeout is configured through {@link SoConfig#getSoReadTimeoutMs()}. A
 * value of {@code -1} disables idle detection.</p>
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
