package net.hasor.neta.channel.sctp;
import com.sun.nio.sctp.Notification;
import net.hasor.neta.channel.SoUserEventData;

/**
 * {@link SoUserEventData} wrapper for SCTP protocol notifications.
 * <p>SCTP can emit association and path-management events that are not normal
 * payload messages, such as association state changes, peer address changes,
 * send failures, and graceful shutdown notifications. This class carries the
 * original {@link Notification} through Neta's user-event pipeline.
 * <p>Handlers should inspect {@link #getNotification()} and cast it to the
 * specific SCTP notification subtype they care about.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see Notification
 * @see net.hasor.neta.channel.SoUserEvent
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
