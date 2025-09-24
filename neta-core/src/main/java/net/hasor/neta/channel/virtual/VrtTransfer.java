package net.hasor.neta.channel.virtual;
import net.hasor.cobble.CollectionUtils;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.handler.PlayLoad;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public class VrtTransfer {
    private final NetManager                       manager;
    private final Map<Long, List<VrtTransferLink>> distributeMap;

    /**
     * Constructor for VrtTransfer.
     * @param manager The NetManager instance to manage the network channels.
     */
    public VrtTransfer(NetManager manager) {
        this.distributeMap = new LinkedHashMap<>();
        this.manager = manager;
        this.manager.getContext().subscribe(this.subscribeSelect(), this::distribute);
    }

    private Predicate<PlayLoad> subscribeSelect() {
        return playLoad -> this.distributeMap.containsKey(playLoad.getSource().getChannelId());
    }

    private void distribute(PlayLoad playLoad) {
        if (!playLoad.isOutbound()) {
            return;
        }

        List<VrtTransferLink> linkList = this.distributeMap.get(playLoad.getSource().getChannelId());
        if (CollectionUtils.isEmpty(linkList)) {
            return;
        }

        for (VrtTransferLink link : linkList) {
            link.target.onReceive(((Function) link.convert).apply(playLoad.getData()));
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
    public void linkTo(VrtChannel from, VrtChannel to, Function<?, ?> convert) throws SocketException {
        if (from.getContext() != to.getContext()) {
            throw new SocketException("channels need same NetaManager");
        }
        if (from.getChannelId() == to.getChannelId()) {
            throw new SocketException("cannot create self link");
        }
        if (from.getContext() != this.manager.getContext() || to.getContext() != this.manager.getContext()) {
            throw new SocketException("channels and VrtTransfer need same NetaManager.");
        }

        convert = convert == null ? o -> o : convert;

        List<VrtTransferLink> linkList = this.distributeMap.computeIfAbsent(from.getChannelId(), c -> new ArrayList<>());
        if (linkList.stream().anyMatch(l -> l.target.getChannelId() == to.getChannelId())) {
            throw new SocketException("link " + from.getChannelId() + " -> " + to.getChannelId() + " already exists");
        }

        linkList.add(new VrtTransferLink(to, convert));
    }
}