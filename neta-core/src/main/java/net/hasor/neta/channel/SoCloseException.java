/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Base exception for channel states related to closure.
 * <p>This exception family covers both fully closed channels and transport-specific partial-close
 * signals. {@link SoCloseException} itself represents a terminal close state, where the channel
 * should no longer be considered usable. {@link SoInputCloseException} is more specific: it means
 * the inbound side has been closed while outbound writes may still be possible.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoInputCloseException
 * @see SoChannel#isClose()
 */
public class SoCloseException extends SoException {
    public SoCloseException(String s) {
        super(s);
    }

    public SoCloseException(String s, Throwable e) {
        super(s, e);
    }
}
