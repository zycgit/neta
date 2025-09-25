package net.hasor.neta.channel.virtual;
import net.hasor.cobble.CollectionUtils;
import net.hasor.cobble.NumberUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.SubscribeHolder;
import net.hasor.neta.handler.PlayLoad;

import java.lang.reflect.Array;
import java.net.SocketException;
import java.util.*;
import java.util.function.Predicate;

public class VrtTransfer {
    private static final Logger                           logger = Logger.getLogger(VrtTransfer.class);
    private static final Random                           RANDOM;
    private final        NetManager                       manager;
    private final        Map<Long, List<VrtTransferLink>> distributeMap;
    private final        SubscribeHolder                  subscribeHolder;
    private final        boolean                          asynchronous;
    private              int                              batchSize;
    private              int                              lossRate;

    static {
        RANDOM = new Random(System.currentTimeMillis());
    }

    /**
     * Constructor for VrtTransfer.
     * @param manager The NetManager instance to manage the network channels.
     */
    public VrtTransfer(NetManager manager) {
        this(manager, false);
    }

    /**
     * Constructor for VrtTransfer.
     * @param manager The NetManager instance to manage the network channels.
     */
    public VrtTransfer(NetManager manager, boolean asynchronous) {
        this.manager = manager;
        this.distributeMap = new LinkedHashMap<>();
        this.asynchronous = asynchronous;
        this.batchSize = 1;
        this.lossRate = 0;
        this.subscribeHolder = this.manager.getContext().subscribe(this.playLoadFilter(), this::playLoadDistribute);
    }

    public boolean isAsynchronous() {
        return this.asynchronous;
    }

    public int getBatchSize() {
        return this.batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        logger.warn("set batchSize to " + this.batchSize);
    }

    public int getLossRate() {
        return this.lossRate;
    }

    public void setLossRate(int lossRate) {
        this.lossRate = NumberUtils.between(lossRate, 0, 100);
        logger.warn("set lossRate to " + this.lossRate + "%");
    }

    private Predicate<PlayLoad> playLoadFilter() {
        return playLoad -> playLoad.isOutbound() && this.distributeMap.containsKey(playLoad.getSource().getChannelId());
    }

    private void playLoadDistribute(PlayLoad playLoad) {
        long srcChannelId = playLoad.getSource().getChannelId();
        List<VrtTransferLink> linkList = this.distributeMap.get(srcChannelId);
        if (CollectionUtils.isEmpty(linkList)) {
            return;
        }

        for (VrtTransferLink link : linkList) {
            long dstChannelId = link.target.getChannelId();
            if (this.lossRate > 0) {
                int nextInt = (int) (RANDOM.nextFloat() * 100);
                if (nextInt > this.lossRate) {
                    logger.warn("transfer " + srcChannelId + " -> " + dstChannelId + ", packet loss rate of " + this.lossRate + "%, packet loss occurred.");
                    continue;
                }
            }

            link.cacheQueue.offerMessage(playLoad);
            link.cacheQueue.sndSubmit();
            logger.info("transfer " + srcChannelId + " -> " + dstChannelId + ", packet has been accepted, queueSize " + link.cacheQueue.queueSize());
            link.onReceive(this.asynchronous, this.batchSize);
        }
    }

    /**
     * Links two virtual channels with a conversion function.
     * @param from The source VrtChannel.
     * @param to The target VrtChannel.
     * @param convert The conversion function to apply to the data.
     * @throws IllegalArgumentException If the from or to channels do not belong to the same NetaManager.
     * @throws IllegalStateException If the link already exists.
     */
    public void linkTo(VrtChannel from, VrtChannel to, VrtTransferHandler convert) throws SocketException {
        Objects.requireNonNull(convert, "convert is null.");
        if (from.getContext() != to.getContext()) {
            throw new SocketException("channels need same NetaManager");
        }
        if (from.getChannelId() == to.getChannelId()) {
            throw new SocketException("cannot create self link");
        }
        if (from.getContext() != this.manager.getContext() || to.getContext() != this.manager.getContext()) {
            throw new SocketException("channels and VrtTransfer need same NetaManager.");
        }

        List<VrtTransferLink> linkList = this.distributeMap.computeIfAbsent(from.getChannelId(), c -> new ArrayList<>());
        if (linkList.stream().anyMatch(l -> l.target.getChannelId() == to.getChannelId())) {
            throw new SocketException("link " + from.getChannelId() + " -> " + to.getChannelId() + " already exists");
        }

        linkList.add(new VrtTransferLink(to, convert));
    }

    public static VrtTransferHandler duplicate() {
        return (src, dst) -> {
            while (src.hasMore()) {
                Object data = src.takeMessage().getData();
                if (data == null) {
                    dst.offerMessage((Object) null);
                } else if (data instanceof ByteBuf) {
                    dst.offerMessage(((ByteBuf) data).copy());
                } else if (data instanceof List) {
                    dst.offerMessage(new ArrayList<>((List) data));
                } else if (data.getClass().isArray()) {
                    Class<?> componentType = data.getClass().getComponentType();

                    int arrayLength = Array.getLength(data);
                    Object newArray = Array.newInstance(componentType, arrayLength);
                    System.arraycopy(data, 0, newArray, 0, arrayLength);
                    dst.offerMessage(newArray);
                } else {
                    throw new UnsupportedOperationException("duplicate unsupported type " + data.getClass());
                }
            }
        };
    }

    public static VrtTransferHandler direct() {
        return (src, dst) -> {
            while (src.hasMore()) {
                dst.offerMessage(src.takeMessage().getData());
            }
        };
    }
}