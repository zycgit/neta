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
 * Thrown when the remote peer has shut down the write side of its socket (TCP FIN), causing
 * the local channel's input stream to report EOF.
 * <p>This exception represents a TCP <em>half-close</em>: the peer will send no more data,
 * but the local side may still flush any buffered outbound data before closing the connection.
 * Typical handling:
 * <ol>
 *   <li>Flush any remaining application-level outbound data.</li>
 *   <li>Call {@link SoChannel#close()} to complete a graceful four-way close.</li>
 * </ol>
 * <p>Note: SCTP and UDP do not support half-close semantics; this exception is only
 * meaningful for TCP (and TLS-over-TCP) channels.
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