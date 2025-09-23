package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.handler.PlayLoad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public class VrtTransfer {
    private final NetManager            manager;
    private final List<Long>            sourceList;
    private       boolean               closed;
    private final Map<Long, List<Long>> distributeMap;

    public VrtTransfer(NetManager manager) {
        this.sourceList = new ArrayList<>();
        this.distributeMap = new LinkedHashMap<>();
        this.manager = manager;
        this.manager.getContext().subscribe(this.subscribeSelect(), this::distribute);
        this.closed = false;
    }

    private Predicate<PlayLoad> subscribeSelect() {
        return playLoad -> this.sourceList.contains(playLoad.getSource().getChannelId());
    }

    private void distribute(PlayLoad playLoad) {
        //
    }

    public <IN, OUT> void linkTo(VrtChannel client, VrtChannel server, Function<IN, OUT> convert) {

    }
    // if (transferMode == TransferMode.ToTarget || transferMode == TransferMode.Both) {
    //     transfer.source.addEventListener(EventBus, (EventListener) data -> {
    //         transfer.sourceQueue.offerMessage(data.getData());
    //     });
    // }
    // if (transferMode == TransferMode.ToSource || transferMode == TransferMode.Both) {
    //     transfer.target.addEventListener(EventBus, (EventListener) data -> {
    //         transfer.targetQueue.offerMessage(data.getData());
    //     });
    // }
}
