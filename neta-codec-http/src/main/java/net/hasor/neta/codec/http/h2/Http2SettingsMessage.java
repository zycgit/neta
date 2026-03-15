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
package net.hasor.neta.codec.http.h2;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Semantic SETTINGS message produced from a SETTINGS frame payload. */
public class Http2SettingsMessage extends AbstractHttp2Message {
    private final boolean            ack;
    private final Map<Integer, Long> settings;

    public Http2SettingsMessage(boolean ack, Map<Integer, Long> settings) {
        this.ack = ack;
        this.settings = settings == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    @Override
    public Type messageType() {
        return Type.SETTINGS;
    }

    public boolean ack() {
        return this.ack;
    }

    public Map<Integer, Long> settings() {
        return this.settings;
    }

    @Override
    public String toString() {
        return "Http2SettingsMessage{ack=" + this.ack + ", settings=" + this.settings + '}';
    }
}