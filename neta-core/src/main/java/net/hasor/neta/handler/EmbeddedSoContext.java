///*
// * Copyright 2008-2009 the original author or authors.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package net.hasor.neta.handler;
//import net.hasor.cobble.logging.Logger;
//import net.hasor.neta.bytebuf.ByteBufAllocator;
//import net.hasor.neta.channel.NetConfig;
//import net.hasor.neta.channel.NetManager;
//import net.hasor.neta.channel.SoChannel;
//import net.hasor.neta.channel.SoContext;
//
//import java.net.SocketAddress;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.atomic.AtomicLong;
//
///**
// * Base class for {@link SoContext} implementations that are used in an embedded fashion.
// * @author 赵永春 (zyc@hasor.net)
// * @version : 2023-09-24
// */
//@Deprecated
//public class EmbeddedSoContext implements SoContext {
//    private static final Logger                  logger = Logger.getLogger(EmbeddedSoContext.class);
//    private static final AtomicLong              nextID = new AtomicLong();
//    private final        NetConfig               config;
//    private final        ByteBufAllocator        allocator;
//    private final        Map<Long, SoChannel<?>> channelMap;
//
//    public EmbeddedSoContext() {
//        this(newSoConfig());
//    }
//
//    public EmbeddedSoContext(NetConfig config) {
//        this.config = config;
//        this.allocator = config.getBufAllocator() == null ? ByteBufAllocator.DEFAULT : config.getBufAllocator();
//        this.channelMap = new ConcurrentHashMap<>();
//    }
//
//    protected static NetConfig newSoConfig() {
//        NetConfig config = new NetConfig();
//        config.setPrintLog(true);
//        return config;
//    }
//
//    protected static long nextID() {
//        return nextID.incrementAndGet();
//    }
//
//    public static long lastChannelID() {
//        return nextID.get();
//    }
//
//    public static void resetChannelID() {
//        resetChannelID(0);
//    }
//
//    public static void resetChannelID(int idStart) {
//        nextID.set(idStart);
//    }
//
//    @Override
//    public NetConfig getConfig() {
//        return this.config;
//    }
//
//    @Override
//    public ByteBufAllocator getByteBufAllocator() {
//        return this.allocator;
//    }
//
//    @Override
//    public SocketAddress getRemoteAddress(long channelID) {
//        SoChannel<?> channel = this.channelMap.get(channelID);
//        if (channel == null || channel.isClose()) {
//            return null;
//        } else {
//            return channel.getRemoteAddr();
//        }
//    }
//
//    /** new channel. */
//    public void openChannel(SoChannel<?> channel) {
//        logger.info("channel(" + channel.getChannelId() + ") created.");
//        this.channelMap.put(channel.getChannelId(), channel);
//    }
//
//    /** test the channel has been closed */
//    @Override
//    public boolean isClose(long channelID) {
//        SoChannel<?> channel = this.channelMap.get(channelID);
//        return channel == null || channel.isClose();
//    }
//
//    @Override
//    public SoChannel<?> findChannel(long channelID) {
//        return this.channelMap.get(channelID);
//    }
//
//    @Override
//    public NetManager getNetManager() {
//        throw new UnsupportedOperationException();
//    }
//
//    /** force close network channel, like {@link SoChannel#closeNow()} */
//    protected void closeChannel(long channelID, String message) {
//        logger.info("channel(" + channelID + ") close in progress, " + message);
//        SoChannel<?> channel = this.channelMap.get(channelID);
//        this.channelMap.remove(channelID);
//
//        EmbeddedChannel netChannel = (EmbeddedChannel) channel;
//        netChannel.protoStack.onClose(netChannel.protoCtx);
//
//        logger.info("channel(" + channelID + ") closed.");
//    }
//
//    /**
//     * that are used in an embedded fashion, connected two {@link EmbeddedTransfer} makes it a server/client.
//     * @param client client side {@link EmbeddedTransfer}
//     * @param server server side {@link EmbeddedTransfer}
//     */
//    public EmbeddedTransfer joinChannel(EmbeddedChannel client, EmbeddedChannel server) {
//        return new EmbeddedTransfer(client, server);
//    }
//}