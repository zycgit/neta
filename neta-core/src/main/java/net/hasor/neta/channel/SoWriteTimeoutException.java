/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when an outbound send operation does not complete within the configured timeout window.
 * <p>When a channel stays in a state that is technically writable but cannot finish writing for a
 * long time, the underlying send task throws this exception. The concrete root cause depends on the
 * transport protocol and peer behavior, but the common symptom is that queued outbound work cannot
 * continue progressing within the allowed time.</p>
 * <p>Transport-layer write timeout is configured through {@link SoConfig#getSoWriteTimeoutMs()}. A
 * value of {@code -1} disables the timeout.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoReadTimeoutException
 * @see SoTimeoutException
 */
public class SoWriteTimeoutException extends SoTimeoutException {
    public SoWriteTimeoutException(String msg) {
        super(msg);
    }
}
