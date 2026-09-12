/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.client;
/** 用于内部解耦 */
@FunctionalInterface
interface TelClientEventListener {
    void onEventClient();
}
