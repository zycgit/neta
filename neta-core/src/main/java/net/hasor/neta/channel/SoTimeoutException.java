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
 * Base exception for all channel timeout errors in Neta.
 * <p>The timeout exception hierarchy is:
 * <pre>
 * SoException
 *   └── SoTimeoutException
 *         ├── SoConnectTimeoutException – connection not established within the configured deadline
 *         ├── SoReadTimeoutException    – no inbound data received within the read-idle period
 *         └── SoWriteTimeoutException   – outbound data not flushed within the write-idle period
 * </pre>
 * <p>Catch {@code SoTimeoutException} to handle all timeout scenarios with a single handler,
 * or catch a specific subclass to distinguish between connect, read, and write timeouts.
 * Timeout values are configured per-channel via the corresponding {@code SoConfig} properties.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoConnectTimeoutException
 * @see SoReadTimeoutException
 * @see SoWriteTimeoutException
 */
public class SoTimeoutException extends SoException {
    public SoTimeoutException(String s) {
        super(s);
    }

    public SoTimeoutException(String s, Throwable e) {
        super(s, e);
    }
}