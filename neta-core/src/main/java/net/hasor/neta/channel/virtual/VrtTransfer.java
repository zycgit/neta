package net.hasor.neta.channel.virtual;
import net.hasor.cobble.CollectionUtils;
import net.hasor.cobble.NumberUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

import java.lang.reflect.Array;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public class VrtTransfer {
    private static final Logger logger = Logger.getLogger(VrtTransfer.class);
    private static final Random RANDOM;

    static {
        RANDOM = new Random(System.currentTimeMillis());
    }

    private final NetManager                       manager;
    private final Map<Long, List<VrtTransferLink>> distributeMap;
    private final SubscribeHolder                  subscribeHolder;
    private final boolean                          asynchronous;
    private final AtomicBoolean                    closed;
    private       int                              batchSize;
    private       int                              lossRate;

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
        this.closed = new AtomicBoolean(false);
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
                    dst.offerMessage(new CopyOnWriteArrayList<>((List) data));
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

    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.subscribeHolder.unSubscribe();
            this.distributeMap.clear();
        }
    }

    private Predicate<PlayLoad> playLoadFilter() {
        return playLoad -> playLoad.isOutbound() && this.distributeMap.containsKey(playLoad.getSource().getChannelId());
    }

    private void playLoadDistribute(PlayLoad playLoad) {
        if (this.closed.get()) {
            return;// is close
        }

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

            PlayLoad p = playLoad;
            if (playLoad.getData() instanceof ByteBuf) {
                ByteBuf byteBuf = ((ByteBuf) playLoad.getData()).copy();
                if (playLoad.isSuccess()) {
                    p = PlayLoadObject.of(playLoad.getSource(), byteBuf, playLoad.isInbound(), playLoad.isOutbound());
                } else {
                    p = PlayLoadObject.ofError(playLoad.getSource(), playLoad.getError(), playLoad.isInbound(), playLoad.isOutbound());
                }
            }

            link.cacheQueue.offerMessage(p);
            link.cacheQueue.sndSubmit();
            logger.info("transfer " + srcChannelId + " -> " + dstChannelId + ", packet has been accepted, queueSize " + link.cacheQueue.queueSize());

            if (this.asynchronous) {
                SoContextService s = (SoContextService) link.target.getContext();
                s.submitSoTask(new SimpleTask(() -> link.onReceive(this.batchSize)), link.target);
            } else {
                link.onReceive(this.batchSize);
            }
        }
    }

    /**
     * Links two virtual channels with a conversion function.
     * @param from The source VrtChannel.
     * @param to The target VrtChannel.
     * @param rcvConvert The conversion function to apply to the data.
     * @throws IllegalArgumentException If the from or to channels do not belong to the same NetaManager.
     * @throws IllegalStateException If the link already exists.
     */
    public void linkTo(VrtChannel from, VrtChannel to, VrtTransferHandler rcvConvert) throws SocketException {
        Objects.requireNonNull(rcvConvert, "rcvConvert is null.");

        if (this.closed.get()) {
            throw new SocketException("VrtTransfer is closed.");
        }
        if (from.getContext() != to.getContext()) {
            throw new SocketException("channels need same NetaManager");
        }
        if (from.getChannelId() == to.getChannelId()) {
            throw new SocketException("cannot create self link");
        }
        if (from.getContext() != this.manager.getContext() || to.getContext() != this.manager.getContext()) {
            throw new SocketException("channels and VrtTransfer need same NetaManager.");
        }

        // create link list if not exists.
        List<VrtTransferLink> linkList = this.distributeMap.get(from.getChannelId());
        if (linkList == null) {
            linkList = new CopyOnWriteArrayList<>();
            this.distributeMap.put(from.getChannelId(), linkList);

            // when source channel closed, remove all distribute.
            from.onClose(channel -> {
                logger.info("unlink " + from.getChannelId() + " -> all.");
                this.distributeMap.remove(from.getChannelId());
            });
        }

        //
        if (linkList.stream().anyMatch(l -> l.target.getChannelId() == to.getChannelId())) {
            throw new SocketException("link " + from.getChannelId() + " -> " + to.getChannelId() + " already exists");
        } else {
            to.onClose(channel -> {
                this.removeLink(from, to);
            });
            linkList.add(new VrtTransferLink((SoContextService) this.manager.getContext(), to, rcvConvert));
        }
    }

    private void removeLink(VrtChannel from, VrtChannel to) {
        logger.info("unlink " + from.getChannelId() + " -> " + to.getChannelId() + ".");
        List<VrtTransferLink> links = this.distributeMap.get(from.getChannelId());
        if (links != null) {
            links.removeIf(l -> l.target.getChannelId() == to.getChannelId());
        }
    }
}