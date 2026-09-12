/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Network event fired in the <em>SND</em> direction, tail to head, just before a locally initiated
 * channel close is actually performed.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-05
 */
public final class SoCloseEvent implements SoEventData {
    /** Singleton instance. */
    public static final SoCloseEvent INSTANCE = new SoCloseEvent();

    private SoCloseEvent() {
    }
}
