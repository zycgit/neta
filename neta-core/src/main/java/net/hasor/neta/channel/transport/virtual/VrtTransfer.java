package net.hasor.neta.channel.transport.virtual;
import java.lang.reflect.Array;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.hasor.cobble.CollectionUtils;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.NumberUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * Route outbound payloads between linked virtual channels.
 * <p>This type is the core in-memory transport bus in the virtual package. It subscribes to
 * {@link PlayLoad} objects published by the shared {@link SoContext}, finds all
 * {@link VrtTransferLink} instances associated with the source channel, optionally simulates packet
 * loss, and then hands the payload to target channels after conversion and batching.
 * <p><b>Data path:</b>
 * <pre>
 *   sender NetChannel.sendData(...)
 *       -> VrtAsyncChannel.write(...)
 *       -> context.trigger(PlayLoad)
 *       -> VrtTransfer subscription callback
 *       -> distributeMap[sourceChannelId]
 *       -> VrtTransferLink.cacheQueue/tempQueue
 *       -> target.onReceive(...)
 * </pre>
 * <ul>
 *   <li><b>Link scope:</b> the routing table is indexed by source channel ID, and each source
 *       channel can fan out to multiple target links.</li>
 *   <li><b>Conversion:</b> {@link #duplicate()} creates per-receiver copies of {@link ByteBuf}
 *       payloads, while {@link #direct()} forwards references as-is.</li>
 *   <li><b>Delivery mode:</b> when asynchronous mode is enabled, each target delivery is submitted
 *       back to the manager executor; otherwise it runs inline on the current thread.</li>
 *   <li><b>Packet-loss simulation:</b> in the current implementation, {@code lossRate} is used as
 *       a threshold filter. A value of {@code 0} means no packet loss, and larger values make a
 *       payload less likely to be skipped because loss occurs only when {@code random(0..99) > lossRate}.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtTransfer {
    private static final Logger logger = Logger.getLogger(VrtTransfer.class);
    private static final Random RANDOM;

    static {
        RANDOM = new Random(System.currentTimeMillis());
    }

    private final    NetManager                       manager;
    private final    Map<Long, List<VrtTransferLink>> distributeMap;
    private final    SubscribeHolder                  subscribeHolder;
    private final    boolean                          asynchronous;
    private final    AtomicBoolean                    closed;
    private volatile int                              batchSize;
    private volatile int                              lossRate;

    /**
     * Create a synchronous virtual transport object.
     * @param manager the NetManager instance
     */
    public VrtTransfer(NetManager manager) {
        this(manager, false);
    }

    /**
     * Create a virtual transport object.
     * @param manager the NetManager instance
     * @param asynchronous whether delivery is asynchronous
     */
    public VrtTransfer(NetManager manager, boolean asynchronous) {
        this.manager = manager;
        this.distributeMap = new ConcurrentHashMap<>();
        this.asynchronous = asynchronous;
        this.batchSize = 1;
        this.lossRate = 0;
        this.subscribeHolder = this.manager.getContext().subscribe(this.playLoadFilter(), SubscribeMode.SYNC, this::playLoadDistribute);
        this.closed = new AtomicBoolean(false);
    }

    /**
     * Return a receive-side converter that processes data with copy semantics.
     * @return the receive-side conversion handler
     */
    public static VrtTransferHandler duplicate() {
        return (src, dst) -> {
            while (src.hasMore()) {
                PlayLoad playLoad = src.takeMessage();
                if (!playLoad.isSuccess()) {
                    logger.error(playLoad.getError().getMessage(), playLoad.getError());
                    throw ExceptionUtils.toRuntime(playLoad.getError());
                }

                Object data = playLoad.getData();
                if (data == null) {
                    dst.offerMessage((Object) null);
                } else if (data instanceof ByteBuf) {
                    ByteBuf byteBuf = (ByteBuf) data;
                    try {
                        dst.offerMessage(byteBuf.copy());
                    } finally {
                        byteBuf.free();
                    }
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

    /**
     * Return a receive-side converter that processes data with pass-through semantics.
     * @return the receive-side conversion handler
     */
    public static VrtTransferHandler direct() {
        return (src, dst) -> {
            while (src.hasMore()) {
                dst.offerMessage(src.takeMessage().getData());
            }
        };
    }

    /**
     * Determine whether the current transport uses asynchronous delivery.
     * @return true if asynchronous delivery is enabled
     */
    public boolean isAsynchronous() {
        return this.asynchronous;
    }

    /**
     * Return the current batching threshold.
     * @return the batch size
     */
    public int getBatchSize() {
        return this.batchSize;
    }

    /**
     * Set the batching threshold.
     * @param batchSize the batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        logger.warn("set batchSize to " + this.batchSize);
    }

    /**
     * Return the current packet-loss configuration.
     * @return the packet loss rate
     */
    public int getLossRate() {
        return this.lossRate;
    }

    /**
     * Set the current packet-loss configuration.
     * @param lossRate the packet loss rate
     */
    public void setLossRate(int lossRate) {
        this.lossRate = NumberUtils.between(lossRate, 0, 100);
        logger.warn("set lossRate to " + this.lossRate + "%");
    }

    /**
     * Close the current virtual transport and release its subscription and routing table.
     */
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.subscribeHolder.unSubscribe();
            this.distributeMap.clear();
        }
    }

    /**
     * Create the filter used to select forwardable payloads.
     * @return the payload filter
     */
    private Predicate<PlayLoad> playLoadFilter() {
        return playLoad -> playLoad.isOutbound() && this.distributeMap.containsKey(playLoad.getSource().getChannelId());
    }

    /**
     * Distribute one payload to all target links associated with the source channel.
     * @param playLoad the payload to distribute
     */
    private void playLoadDistribute(PlayLoad playLoad) {
        if (this.closed.get()) {
            return;// Already closed.
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
                if (playLoad.isSuccess()) {
                    ByteBuf byteBuf = ((ByteBuf) playLoad.getData()).copy();
                    p = PlayLoadObject.of(playLoad.getSource(), byteBuf, playLoad.isInbound(), playLoad.isOutbound());
                } else {
                    p = PlayLoadObject.ofError(playLoad.getSource(), playLoad.getError(), playLoad.isInbound(), playLoad.isOutbound());
                }
            }

            link.cacheQueue.offerMessage(p);
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
     * Establish a transport link with conversion capability between two virtual channels.
     * @param from the source channel
     * @param to the target channel
     * @param rcvConvert the receive-side conversion handler
     * @throws SocketException if link creation fails
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

        // Create the link list if it does not exist.
        List<VrtTransferLink> linkList = this.distributeMap.get(from.getChannelId());
        if (linkList == null) {
            linkList = new CopyOnWriteArrayList<>();
            this.distributeMap.put(from.getChannelId(), linkList);

            // Remove all routing entries when the source channel closes.
            from.onClose(channel -> {
                logger.info("unlink " + from.getChannelId() + " -> all.");
                this.distributeMap.remove(from.getChannelId());
            });
        }

        // Register the target-side close callback and create the link.
        if (linkList.stream().anyMatch(l -> l.target.getChannelId() == to.getChannelId())) {
            throw new SocketException("link " + from.getChannelId() + " -> " + to.getChannelId() + " already exists");
        } else {
            to.onClose(channel -> {
                this.removeLink(from, to);
            });
            linkList.add(new VrtTransferLink((SoContextService) this.manager.getContext(), to, rcvConvert));
        }
    }

    /**
     * Remove one link from the source channel to the target channel.
     * @param from the source channel
     * @param to the target channel
     */
    private void removeLink(VrtChannel from, VrtChannel to) {
        logger.info("unlink " + from.getChannelId() + " -> " + to.getChannelId() + ".");
        List<VrtTransferLink> links = this.distributeMap.get(from.getChannelId());
        if (links != null) {
            links.removeIf(l -> l.target.getChannelId() == to.getChannelId());
        }
    }
}