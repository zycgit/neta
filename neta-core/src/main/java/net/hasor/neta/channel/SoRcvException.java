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
 * Thrown when a low-level I/O error occurs while reading from a {@link SoChannel}.
 * <p>It wraps operating-system-level socket errors that are not already covered by more specific
 * subclasses, for example read timeout is represented by {@link SoReadTimeoutException} and
 * half-close by {@link SoInputCloseException}. Common trigger scenarios include:</p>
 * <ul>
 *   <li>Receiving an unexpected TCP RST from the remote peer during an active read.</li>
 *   <li>A NIC or network stack failure causing an unrecoverable I/O error.</li>
 *   <li>A previously valid file descriptor being closed by another thread.</li>
 * </ul>
 * <p>Once this exception is thrown, the channel should be considered unusable and subsequent reads
 * will usually fail immediately. Callers should close the channel and release associated resources.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoReadTimeoutException
 * @see SoCloseException
 */
public class SoRcvException extends SoException {
    public SoRcvException(String s) {
        super(s);
    }

    public SoRcvException(String s, Throwable e) {
        super(s, e);
    }
}