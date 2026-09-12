/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when the peer closes the write side of its socket (TCP FIN) and the local channel input
 * stream reports EOF.
 * <p>This exception represents the TCP <em>half-close</em> state: the peer will not send any more
 * data, but the local side may still flush remaining outbound data before the connection is fully
 * closed. A common handling pattern is:</p>
 * <ol>
 *   <li>Send or flush any remaining application outbound data.</li>
 *   <li>Call {@link SoChannel#close()} to complete the normal four-way shutdown sequence.</li>
 * </ol>
 * <p>Note that SCTP and UDP do not support half-close semantics, so this exception only applies to
 * TCP channels, including TLS streams built on top of TCP.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoCloseException
 */
public class SoInputCloseException extends SoCloseException {
    public SoInputCloseException(String s) {
        super(s);
    }

    public SoInputCloseException(String s, Throwable e) {
        super(s, e);
    }
}
