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
package net.hasor.neta.channel.udp;
/**
 * Represents an identifier for a UDP channel, encapsulating the remote ID.
 * <p>
 * This class is used to uniquely identify a UDP connection based on the remote ID.
 * It provides a simple way to access the remote ID associated with a specific UDP channel.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
class UdpIdentifier {
    private final String remoteId;

    /**
     * Constructs a new UdpIdentifier with the specified remote ID.
     * @param remoteId the remote ID of the UDP channel
     */
    public UdpIdentifier(String remoteId) {
        this.remoteId = remoteId;
    }

    @Override
    public String toString() {
        return this.remoteId;
    }
}