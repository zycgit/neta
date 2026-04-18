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
import java.util.Objects;

/**
 * Key that identifies a websocket endpoint inside a shared registry.
 * <p>
 * HTTP/1.x uses a single connection-scope key. HTTP/2 and newer transports
 * can use stream-scope keys.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-08
 */
public final class WebSocketRegistryKey {
    private static final WebSocketRegistryKey CONNECTION_SCOPE = new WebSocketRegistryKey(null);
    private final Long                        streamId;

    private WebSocketRegistryKey(Long streamId) {
        this.streamId = streamId;
    }

    /**
     * Return the default connection-scope endpoint key.
     */
    public static WebSocketRegistryKey connectionScope() {
        return CONNECTION_SCOPE;
    }

    /**
     * Return a stream-scope endpoint key.
     * @param streamId positive stream identifier
     */
    public static WebSocketRegistryKey streamScope(long streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be greater than 0.");
        }
        return new WebSocketRegistryKey(streamId);
    }

    /**
     * Return whether this key represents the connection-scope endpoint.
     */
    public boolean isConnectionScope() {
        return this.streamId == null;
    }

    /**
     * Return the stream identifier when this key represents a stream-scope endpoint.
     */
    public Long streamId() {
        return this.streamId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WebSocketRegistryKey)) {
            return false;
        }
        WebSocketRegistryKey that = (WebSocketRegistryKey) obj;
        return Objects.equals(this.streamId, that.streamId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.streamId);
    }

    @Override
    public String toString() {
        if (this.streamId == null) {
            return "connection-scope";
        } else {
            return "stream-scope(" + this.streamId + ')';
        }
    }
}