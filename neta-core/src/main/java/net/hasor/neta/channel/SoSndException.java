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
 * Generic failure exception for the outbound send path.
 * <p>When a low-level I/O error occurs during a {@link SoChannel} send action, send scheduling, or
 * outbound pipeline processing, and the failure is neither a close-state issue nor a timeout-state
 * issue, it is typically normalized to this exception.</p>
 * <p>This is the base type for send-side exceptions. At present, the only more specific subtype is
 * {@link SoUnfinishedSndException}, which indicates that pending data still remained in the send
 * queue when the channel closed.</p>
 * <p>Two details are worth noting:</p>
 * <ul>
 *   <li>{@link SoWriteTimeoutException} also happens on the send side, but it belongs to the
 *   timeout exception family rather than extending this type.</li>
 *   <li>{@link SoSndException} only indicates that the current send flow failed. It does not by
 *   itself imply that the channel has already closed or that the entire send queue has been
 *   cleared.</li>
 * </ul>
 * <p>Therefore, when application code catches this exception, it should combine the current channel
 * state with the concrete transport behavior to decide whether only the current send should fail,
 * or whether the connection should be closed and remaining queued data discarded.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoUnfinishedSndException
 * @see SoWriteTimeoutException
 * @see SoCloseException
 */
public class SoSndException extends SoException {
    public SoSndException(String s) {
        super(s);
    }

    public SoSndException(String s, Throwable e) {
        super(s, e);
    }
}