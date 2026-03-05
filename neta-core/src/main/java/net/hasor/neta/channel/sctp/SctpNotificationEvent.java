package net.hasor.neta.channel.sctp;

import com.sun.nio.sctp.Notification;
import net.hasor.neta.channel.SoUserEventData;

/**
 * sctp notifications events.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SctpNotificationEvent implements SoUserEventData {
    private final Notification event;

    public SctpNotificationEvent(Notification event) {
        this.event = event;
    }

    public Notification getNotification() {
        return event;
    }
}
