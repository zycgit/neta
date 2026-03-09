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

/**
 * Root checked exception for all Neta channel-layer I/O errors.
 * <p>All Neta socket exceptions extend this class. The full hierarchy is:
 * <pre>
 * IOException
 *   └── SoException
 *         ├── SoBindException        – server channel failed to bind to a local address
 *         ├── SoCloseException       – channel closed or in the process of closing
 *         │   └── SoInputCloseException – remote peer half-closed the inbound stream
 *         ├── SoConnectException     – outbound connection refused or otherwise failed
 *         ├── SoRcvException         – low-level I/O error during data reception
 *         ├── SoSndException         – base for all outbound send failures
 *         │   └── SoUnfinishedSndException – channel closed with data still in the send queue
 *         └── SoTimeoutException     – base for all timeout variants
 *               ├── SoConnectTimeoutException – connect deadline exceeded
 *               ├── SoReadTimeoutException    – read-idle period exceeded
 *               └── SoWriteTimeoutException   – write-idle period exceeded
 * </pre>
 * <p>Catch {@code SoException} to handle any Neta transport error in one place,
 * or catch a specific subclass for targeted recovery — for example, close and
 * retry on {@link SoConnectException}, or send a heartbeat on {@link SoReadTimeoutException}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoBindException
 * @see SoCloseException
 * @see SoConnectException
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