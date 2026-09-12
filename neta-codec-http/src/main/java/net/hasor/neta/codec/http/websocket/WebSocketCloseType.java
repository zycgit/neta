/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Logical websocket close actions that still need transport-specific execution.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-08
 */
enum WebSocketCloseType {
    SEND_CLOSE_AND_TERMINATE,
    TERMINATE
}
