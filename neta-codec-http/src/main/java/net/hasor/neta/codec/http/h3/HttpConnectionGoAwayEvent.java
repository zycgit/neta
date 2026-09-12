/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import java.util.Arrays;
import net.hasor.neta.codec.http.AbstractHttpEvent;
/**
 * 当远端发送 GOAWAY 时发布的连接级优雅关闭信号。
 * <p>
 * 该事件可同时用于 HTTP/2 和 HTTP/3。{@link #lastAcceptedId()} 的含义取决于底层协议：
 * <ul>
 * <li>HTTP/2：最后一个仍被接受的 stream ID</li>
 * <li>HTTP/3：GOAWAY 中编码的最后一个仍被接受的请求或 push 标识</li>
 * </ul>
 */
public class HttpConnectionGoAwayEvent extends AbstractHttpEvent {
    private final long   lastAcceptedId;
    private final long   errorCode;
    private final byte[] debugData;

    /**
     * 创建一个连接级 GOAWAY 事件。
     * @param lastAcceptedId 最后一个仍被接受的标识
     * @param errorCode 协议错误码
     * @param debugData 调试数据
     */
    public HttpConnectionGoAwayEvent(long lastAcceptedId, long errorCode, byte[] debugData) {
        this.lastAcceptedId = lastAcceptedId;
        this.errorCode = errorCode;
        this.debugData = debugData == null ? new byte[0] : Arrays.copyOf(debugData, debugData.length);
    }

    /**
     * 返回对端在 GOAWAY 之后仍接受的最后一个标识。
     */
    public long lastAcceptedId() {
        return this.lastAcceptedId;
    }

    /**
     * 返回协议相关错误码；如果不存在则为 {@code 0}。
     */
    public long errorCode() {
        return this.errorCode;
    }

    /**
     * 返回可选调试负载；如果不存在则返回空数组。
     */
    public byte[] debugData() {
        return Arrays.copyOf(this.debugData, this.debugData.length);
    }
}
