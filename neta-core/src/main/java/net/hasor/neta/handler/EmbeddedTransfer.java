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
package net.hasor.neta.handler;
import net.hasor.cobble.logging.Logger;

import java.util.Objects;

/**
 * Used for data transfer between two {@link EmbeddedChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-26
 */
public class EmbeddedTransfer {
    private static final Logger          logger = Logger.getLogger(EmbeddedTransfer.class);
    private final        EmbeddedChannel client;
    private final        EmbeddedChannel server;

    protected EmbeddedTransfer(EmbeddedChannel client, EmbeddedChannel server) {
        if (!server.isServer()) {
            throw new IllegalStateException("joinChannel failed server Channel must be Server.");
        }
        if (!client.isClient()) {
            throw new IllegalStateException("joinChannel failed client Channel must be Client.");
        }

        this.client = Objects.requireNonNull(client);
        this.server = Objects.requireNonNull(server);
    }

    /** Get the {@link EmbeddedChannel} impersonating the server */
    public EmbeddedChannel getServer() {
        return this.server;
    }

    /** Get the {@link EmbeddedChannel} impersonating the client */
    public EmbeddedChannel getClient() {
        return this.client;
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from server to client.
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     */
    public void transferToClient() {
        this.transferToClient(Integer.MAX_VALUE);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from server to client.
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     * @param copyPacket use turn parameter to determine number messages to send.
     */
    public void transferToClient(int copyPacket) {
        copyPacket = Math.min(this.server.getSndDownSize(), copyPacket);

        if (this.server.isClose()) {
            return;
        }

        Object[] data = this.server.readSndDownArray(copyPacket);
        if (data == null || data.length == 0) {
            return;
        }

        if (!this.client.isClose()) {
            logger.info("transferToClient packet: " + data.length);
            this.client.writeRcvUpArray(data);
        }
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from client to server.
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     */
    public void transferToServer() {
        this.transferToServer(Integer.MAX_VALUE);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from client to server.
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     * @param copyPacket use turn parameter to determine number messages to send.
     */
    public void transferToServer(int copyPacket) {
        copyPacket = Math.min(this.client.getSndDownSize(), copyPacket);

        if (this.client.isClose()) {
            return;
        }

        Object[] data = this.client.readSndDownArray(copyPacket);
        if (data == null || data.length == 0) {
            return;
        }

        if (!this.server.isClose()) {
            logger.info("transferToServer packet: " + data.length);
            this.server.writeRcvUpArray(data);
        }
    }
}