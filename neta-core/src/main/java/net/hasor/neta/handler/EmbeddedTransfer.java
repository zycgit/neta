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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.SoResManager;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Used for data transfer between two {@link EmbeddedChannel}.
 * @version : 2023-10-26
 * @author 赵永春 (zyc@hasor.net)
 */
public class EmbeddedTransfer {
    private final SoResManager    rm;
    private final EmbeddedChannel client;
    private final EmbeddedChannel server;

    protected EmbeddedTransfer(SoResManager rm, EmbeddedChannel client, EmbeddedChannel server) {
        if (!server.isServer()) {
            throw new IllegalStateException("joinChannel failed server Channel must be Server.");
        }
        if (!client.isClient()) {
            throw new IllegalStateException("joinChannel failed client Channel must be Client.");
        }

        this.rm = Objects.requireNonNull(rm);
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
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     */
    public void transferToClient() {
        this.transferToClient(Integer.MAX_VALUE, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from server to client.
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     *
     * @param turn use turn parameter to determine number messages to send.
     */
    public void transferToClient(int turn) {
        this.transferToClient(turn, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from server to client.
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     *
     * @param turn use turn parameter to determine number messages to send.
     * @param duration The interval between two data transmissions
     * @param timeUnit A unit of interval time
     */
    public void transferToClient(int turn, int duration, TimeUnit timeUnit) {
        for (int i = 0; i < turn; i++) {
            if (this.server.isClose()) {
                break;
            }

            Object data = this.server.readSndDown();
            if (data == null) {
                break;
            }
            if (i > 0) {
                ThreadUtils.sleep(duration, timeUnit);
            }

            if (this.client.isClose()) {
                break;
            } else {
                this.client.writeRcvUp(data);
            }
        }
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from client to server.
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     */
    public void transferToServer() {
        this.transferToServer(Integer.MAX_VALUE, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from client to server.
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     *
     * @param turn use turn parameter to determine number messages to send.
     */
    public void transferToServer(int turn) {
        this.transferToServer(turn, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * send the {@link EmbeddedChannel} SND_DOWN endpoint data from client to server.
     *
     * SND_DOWN may be a multiple messages, use turn parameter to determine number messages to send.
     *
     * @param turn use turn parameter to determine number messages to send.
     * @param duration The interval between two data transmissions
     * @param timeUnit A unit of interval time
     */
    public void transferToServer(int turn, int duration, TimeUnit timeUnit) {
        for (int i = 0; i < turn; i++) {
            if (this.client.isClose()) {
                break;
            }

            Object data = this.client.readSndDown();
            if (data == null) {
                break;
            }
            if (i > 0) {
                ThreadUtils.sleep(duration, timeUnit);
            }

            if (this.server.isClose()) {
                break;
            } else {
                this.server.writeRcvUp(data);
            }
        }
    }
}