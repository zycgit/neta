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
 * Thrown when an I/O error occurs while sending data on a {@link SoChannel}.
 * <p>This is the base class for all outbound send failures. More specific subclasses cover
 * common scenarios:
 * <ul>
 *   <li>{@link SoWriteTimeoutException} – the write did not complete within the configured
 *       write-idle timeout.</li>
 *   <li>{@link SoUnfinishedSndException} – the channel was closed before all queued data
 *       could be flushed.</li>
 * </ul>
 * <p>When a {@code SoSndException} is thrown, the outbound queue is cleared and the channel
 * is typically closed. Any pending write {@link net.hasor.cobble.concurrent.future.Future}
 * will be completed exceptionally with this exception.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoWriteTimeoutException
 * @see SoUnfinishedSndException
 */
public class SoSndException extends SoException {
    public SoSndException(String s) {
        super(s);
    }

    public SoSndException(String s, Throwable e) {
        super(s, e);
    }
}