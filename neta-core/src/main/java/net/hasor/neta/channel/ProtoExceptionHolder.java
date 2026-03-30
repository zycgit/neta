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
 * Control handle passed to {@code onError(...)} callbacks so a handler can clear the
 * current exception state.
 * <p>When an unhandled exception escapes a {@link ProtoHandler} or {@link ProtoDuplexer}
 * during message processing, the framework sets an <em>exception flag</em> on the current
 * pipeline invocation and switches subsequent handlers from {@code onMessage} to
 * {@code onError}.  The exception propagates to the end of the pipeline; if nothing clears
 * it the channel is closed.
 * <p>For outbound {@link ProtoContext#sendData(Object)} calls, a failed send attempt does not
 * automatically discard queue-owned messages that are still buffered in the current pipeline
 * stage. If the channel remains open, those messages stay queued and may be processed again on
 * a later send/recovery pass. Final reclamation of such queued data happens in the stack close
 * path only.
 * <p>A handler can intercept the exception and resume normal processing by calling
 * {@link #clear()} from its {@code onError} implementation:
 * <pre>
 * ... → onMessage → [exception] → onError → onError (calls clear()) → onMessage → ...
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoDuplexer
 * @see ProtoHandler
 */
public interface ProtoExceptionHolder {
    /**
        * clear the exception state and continue piple execution
        * <p>You can clear the exception flag with the {@link ProtoExceptionHolder#clear()} method, and piple execution will continue normally</p>
     * <pre>
     *  ... -> onMessage -> onError -> onError(invoker clear) -> onMessage -> ...
     * </pre>
     */
    void clear();
}