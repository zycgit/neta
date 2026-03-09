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
 * Thrown when an outbound send operation does not complete within the configured timeout window.
 * <p>This exception is raised by transport send tasks when the channel stays writable-incomplete for
 * too long. The exact underlying cause depends on the transport and peer behaviour, but the common
 * effect is that queued outbound work cannot make forward progress in time.
 * <p>The transport-level write timeout is configured via {@link SoConfig#getSoWriteTimeoutMs()}.
 * A value of {@code -1} disables that timeout.
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