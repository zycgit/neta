/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when unsent data still remains in the send queue while the channel is closing.
 * <p>Each write request that has not finished flushing receives this exception so the application
 * does not lose data silently. For in-flight writes, the associated
 * {@link net.hasor.cobble.concurrent.future.Future} completes exceptionally with this type instead
 * of ending as a partial success.</p>
 * <p>When the caller uses {@link SoChannel#closeNow()}, this is a normal close signal because that
 * method immediately discards the send queue. It may also be triggered when the remote peer forces
 * a connection reset, such as TCP RST. In contrast, graceful {@link SoChannel#close()} flushes all
 * queued data before closing, so this exception does not normally appear on the regular path.</p>
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
