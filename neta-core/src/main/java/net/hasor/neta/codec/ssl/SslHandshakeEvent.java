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
package net.hasor.neta.codec.ssl;
import net.hasor.neta.channel.SoUserEventData;

/**
 * Handling the SSL handshake
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 */
public class SslHandshakeEvent implements SoUserEventData {
    private final boolean    handshake;
    private final SslContext context;

    public SslHandshakeEvent(boolean handshake, SslContext context) {
        this.handshake = handshake;
        this.context = context;
    }

    public boolean isHandshake() {
        return this.handshake;
    }

    public SslContext getContext() {
        return this.context;
    }
}