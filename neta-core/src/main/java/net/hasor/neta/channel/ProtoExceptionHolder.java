/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Control handle passed into {@code onError(...)} callbacks so a handler can clear the current
 * exception state.
 * <p>When an unhandled exception escapes from {@link ProtoHandler} or {@link ProtoDuplex} during
 * message processing, the framework marks the current pipeline invocation as exceptional and
 * switches subsequent handlers from {@code onMessage} to {@code onError}. The exception continues
 * to propagate until the end of the pipeline. If nothing clears it, the channel is closed on the
 * receive side.</p>
 * <p>For outbound {@link ProtoContext#sendData(Object)} calls, a failed send does not automatically
 * discard messages still owned by the queue at the current pipeline stage. If the channel remains
 * open, those messages stay queued and may be processed again by later send or recovery flows. Such
 * queued data is reclaimed only when the protocol stack close path runs.</p>
 * <p>A handler may call {@link #clear()} in its own {@code onError} implementation to intercept the
 * exception and restore normal processing:</p>
 * <pre>
 * ... → onMessage → [exception] → onError → onError (calls clear()) → onMessage → ...
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoDuplex
 * @see ProtoHandler
 */
public interface ProtoExceptionHolder {
    /**
     * Clear the exception state and continue executing the pipeline.
     * <p>After calling {@link ProtoExceptionHolder#clear()}, the current invocation chain leaves
     * exception-propagation mode and subsequent handlers return to the normal {@code onMessage}
     * path.</p>
     * <pre>
     *  ... -> onMessage -> onError -> onError(invoker clear) -> onMessage -> ...
     * </pre>
     */
    void clear();
}
