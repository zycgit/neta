/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket.extensions;
/**
 * Compatibility alias for the legacy {@code x-webkit-deflate-frame} websocket extension.
 * <p>
 * It reuses the same runtime behavior as {@code deflate-frame} while preserving
 * the negotiated extension name exposed to callers.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class XWebkitDeflateFrameSupport extends DeflateFrameSupport {
    public static final String                      EXTENSION_NAME = "x-webkit-deflate-frame";
    private static final XWebkitDeflateFrameSupport INSTANCE       = new XWebkitDeflateFrameSupport();

    /**
     * Return the singleton support instance.
     * @return singleton support instance
     */
    public static XWebkitDeflateFrameSupport instance() {
        return INSTANCE;
    }

    private XWebkitDeflateFrameSupport() {
        super(EXTENSION_NAME);
    }
}
