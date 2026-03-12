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
package net.hasor.neta.codec.http.websocket;

/**
 * Standard WebSocket close status codes defined by RFC 6455 section 7.4.
 */
public final class WebSocketCloseCode {
    /** 1000 — Normal Closure. */
    public static final int NORMAL_CLOSURE   = 1000;
    /** 1001 — Going Away. */
    public static final int GOING_AWAY       = 1001;
    /** 1002 — Protocol Error. */
    public static final int PROTOCOL_ERROR   = 1002;
    /** 1003 — Unsupported Data. */
    public static final int UNSUPPORTED_DATA = 1003;
    /** 1005 — No Status Received (synthetic, never sent on the wire). */
    public static final int NO_STATUS        = 1005;
    /** 1006 — Abnormal Closure (synthetic, never sent on the wire). */
    public static final int ABNORMAL_CLOSURE = 1006;
    /** 1007 — Invalid frame payload data. */
    public static final int INVALID_DATA     = 1007;
    /** 1008 — Policy Violation. */
    public static final int POLICY_VIOLATION = 1008;
    /** 1009 — Message Too Big. */
    public static final int MESSAGE_TOO_BIG  = 1009;
    /** 1011 — Unexpected Condition. */
    public static final int UNEXPECTED       = 1011;

    private WebSocketCloseCode() {
    }
}