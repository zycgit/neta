/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.codec.http.AbstractHttpEvent;
/**
 * 请求主动重置指定 HTTP/3 stream 的网络事件。
 */
public class Http3ResetEvent extends AbstractHttpEvent {
    public static final long CANCEL         = -1L;
    public static final long INTERNAL_ERROR = -2L;
    public static final long REFUSED        = -3L;

    private final long errorCode;

    /**
     * 创建一个 HTTP/3 reset 事件。
     * @param streamId 要重置的 stream ID
     * @param errorCode reset 原因码
     */
    public Http3ResetEvent(long streamId, long errorCode) {
        this.streamId(streamId);
        this.errorCode = errorCode;
    }

    /**
     * 返回 reset 原因码。
     */
    public long errorCode() {
        return this.errorCode;
    }
}
