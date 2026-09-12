/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.codec.http.HttpProtocolConnectionException;
/**
 * Exception thrown when an invalid compressed header block causes HPACK decoding to fail.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class HpackDecodingException extends HttpProtocolConnectionException {
    /**
     * Creates an HPACK decoding exception with the given error message.
     */
    public HpackDecodingException(String message) {
        super(Http2ErrorCode.COMPRESSION_ERROR, message);
    }
}
