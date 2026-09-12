/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Marks a channel as a child of some parent channel.
 * <p>The lifecycle of a child channel is bound to its parent: when the parent closes, all child
 * channels close as well. During shutdown, the framework closes the parent first so child channels
 * are cleaned up in the correct order.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @see NetChannel
 */
public interface SoSubChannel {
    /**
     * Return the parent channel that owns the current child channel.
     * @return parent channel, never {@code null}
     */
    SoChannel<?> getParent();
}
