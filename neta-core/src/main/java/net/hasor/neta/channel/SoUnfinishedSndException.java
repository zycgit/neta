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
 * Thrown when a channel is closed while it still holds unsent data in the outbound queue.
 * <p>This exception is raised for each pending write that could not be flushed before the
 * channel was torn down, preventing the application from silently losing data. Any
 * {@link net.hasor.cobble.concurrent.future.Future} for an in-flight write will be completed
 * with this exception rather than succeeding with a partial send.
 * <p>This is a normal shutdown signal when the caller uses {@link SoChannel#closeNow()},
 * which discards the send queue immediately. It can also be triggered when the remote peer
 * forcibly resets the connection (TCP RST). By contrast, a graceful {@link SoChannel#close()}
 * flushes all queued data before closing, so this exception is not raised in the normal path.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoSndException
 * @see SoChannel#closeNow()
 * @see SoChannel#close()
 */
public class SoUnfinishedSndException extends SoSndException {
    public SoUnfinishedSndException(String s) {
        super(s);
    }

    public SoUnfinishedSndException(String s, Throwable e) {
        super(s, e);
    }
}