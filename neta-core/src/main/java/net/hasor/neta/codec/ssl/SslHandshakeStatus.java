/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
/**
 * Internal lifecycle state of one channel's TLS session.
 * <p>This enum is maintained by {@link SslHandle} and observed by {@link SslContextBasic} to decide
 * whether the current context is still handshaking, ready for normal encrypted traffic, or already
 * closed at the TLS layer.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-12-19
 * @see SslHandle
 */
enum SslHandshakeStatus {

    NotHandshaking,

    Handshaking,

    Finish,

    /** SSL session ended by a {@code close_notify} alert (either sent or received). */
    Closed,
}
