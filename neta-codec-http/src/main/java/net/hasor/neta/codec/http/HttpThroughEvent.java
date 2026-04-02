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
package net.hasor.neta.codec.http;
/**
 * Network event used to switch the HTTP/1.x codec into or out of transparent mode.
 * <p>
 * When transparent mode is enabled, inbound {@code ByteBuf} frames are wrapped as
 * {@code HttpByteBuf} and forwarded without HTTP parsing. Outbound {@code HttpByteBuf}
 * messages are written as raw bytes without HTTP encoding.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-12
 */
public final class HttpThroughEvent extends AbstractHttpEvent {
    private final boolean enabled;

    public HttpThroughEvent(boolean enabled) {
        this.enabled = enabled;
    }

    public static HttpThroughEvent enable() {
        return new HttpThroughEvent(true);
    }

    public static HttpThroughEvent disable() {
        return new HttpThroughEvent(false);
    }

    public boolean enabled() {
        return this.enabled;
    }

    @Override
    public String toString() {
        return "HttpThroughEvent{" + (this.enabled ? "enabled" : "disabled") + '}';
    }
}