/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.codec.http.HttpProtocolConnectionException;
/**
 * 当压缩后的头块无效，导致 QPACK 解码失败时抛出的异常。
 */
public class QpackDecodingException extends HttpProtocolConnectionException {
    /**
     * 使用给定错误消息创建 QPACK 解码异常。
     */
    public QpackDecodingException(String message) {
        super(Http3ErrorCode.QPACK_DECOMPRESSION_FAILED, message);
    }
}
