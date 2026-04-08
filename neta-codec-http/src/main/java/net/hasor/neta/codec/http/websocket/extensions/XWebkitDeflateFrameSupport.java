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
    public static final  String                     EXTENSION_NAME = "x-webkit-deflate-frame";
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