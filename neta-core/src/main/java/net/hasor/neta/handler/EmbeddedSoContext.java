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
import net.hasor.neta.channel.*;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for {@link SoContext} implementations that are used in an embedded fashion.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class EmbeddedSoContext implements SoContext {
    private static final Logger                  logger = Logger.getLogger(EmbeddedSoContext.class);
    private static final AtomicLong              nextID = new AtomicLong();
    private final        SoConfig                config;
    private final        SoResManager            defaultRm;
    private final        Map<Long, SoChannel<?>> channelMap;

    public EmbeddedSoContext() {
        this(new SoConfig());
    }

    public EmbeddedSoContext(SoConfig config) {
        this.config = config;
        this.channelMap = new ConcurrentHashMap<>();
        this.defaultRm = new DefaultSoResManager(this.config);
    }

    protected static long nextID() {
        return nextID.incrementAndGet();
    }

    @Override
    public SoConfig getConfig() {
        return this.config;
    }

    @Override
    public SoResManager getResourceManager() {
        return this.defaultRm;
    }

    @Override
    public SocketAddress getRemoteAddress(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel == null || channel.isClose()) {
            return null;
        } else {
            return channel.getRemoteAddr();
        }
    }

    /** new channel. */
    public void openChannel(SoChannel<?> channel) {
        logger.info("channel(" + channel.getChannelID() + ") created.");
        this.channelMap.put(channel.getChannelID(), channel);
    }

    /** test the channel has been closed */
    @Override
    public boolean isClose(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }

    /** force close network channel, like {@link SoChannel#closeNow()} */
    protected void closeChannel(long channelID, String message) {
        logger.info("channel(" + channelID + ") close in progress, " + message);
        SoChannel<?> channel = this.channelMap.get(channelID);
        this.channelMap.remove(channelID);

        EmbeddedChannel netChannel = (EmbeddedChannel) channel;
        netChannel.pipeStack.release(netChannel.pipeCtx);

        logger.info("channel(" + channelID + ") closed.");
    }

    /**
     * that are used in an embedded fashion, connected two {@link EmbeddedTransfer} makes it a server/client.
     * @param client client side {@link EmbeddedTransfer}
     * @param server server side {@link EmbeddedTransfer}
     */
    public EmbeddedTransfer joinChannel(EmbeddedChannel client, EmbeddedChannel server) {
        return new EmbeddedTransfer(this.defaultRm, client, server);
    }
}