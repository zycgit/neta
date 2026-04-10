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
import java.io.IOException;
import net.hasor.neta.channel.transport.quic.QuicException;

/**
 * Root checked exception for all I/O failures in the Neta channel layer.
 * <p>Within {@code neta-core}, all channel I/O faults that need to be propagated as checked
 * exceptions derive from this type. The current hierarchy is:</p>
 * <pre>
 * IOException
 *   └── SoException
 *         ├── SoBindException                  – server channel failed to bind the local address
 *         ├── SoCloseException                 – channel is closed or closing
 *         │   └── SoInputCloseException        – peer half-closed the inbound stream
 *         ├── SoConnectException               – outbound connection was refused or failed
 *         ├── QuicException                    – base exception for QUIC protocol-level errors
 *         │   ├── QuicConnectionCloseException – received or sent CONNECTION_CLOSE
 *         │   ├── QuicStreamResetException     – received RESET_STREAM
 *         │   └── QuicStopSendingException     – received STOP_SENDING
 *         ├── SoRcvException                   – low-level I/O failure while receiving data
 *         ├── SoSndException                   – base exception for outbound send failures
 *         │   └── SoUnfinishedSndException     – send queue still contained pending data on close
 *         └── SoTimeoutException               – base exception for all timeout variants
 *               ├── SoConnectTimeoutException  – connection was not established before the deadline
 *               ├── QuicIdleTimeoutException   – QUIC connection or stream idle timeout
 *               ├── SoReadTimeoutException     – read idle time exceeded the configured limit
 *               └── SoWriteTimeoutException    – write idle time exceeded the configured limit
 * </pre>
 * <p>This diagram only covers checked exceptions in {@code neta-core} that extend
 * {@code SoException}. {@link ProtoFullException}, {@code net.hasor.neta.codec.CodecException},
 * and {@code net.hasor.neta.bytebuf.OutOfMemoryPoolException} belong to separate exception
 * hierarchies and are not part of this inheritance tree.</p>
 * <p>Catching {@code SoException} allows uniform handling of Neta transport failures. If more
 * specific recovery is needed, catch concrete subtypes, for example closing and retrying on
 * {@link SoConnectException}, or sending a heartbeat on {@link SoReadTimeoutException}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoBindException
 * @see SoCloseException
 * @see SoConnectException
 * @see QuicException
 * @see SoRcvException
 * @see SoSndException
 * @see SoTimeoutException
 */
public class SoException extends IOException {
    public SoException(String s) {
        super(s);
    }

    public SoException(String s, Throwable e) {
        super(s, e);
    }
}