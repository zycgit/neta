package net.hasor.neta.channel.sctp;
import com.sun.nio.sctp.*;
import net.hasor.neta.channel.SoContextService;

/**
 * Handler for SCTP notifications (Association Change, Shutdown, etc.).
 * Converts native SCTP notifications into Neta framework events.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
final class SctpNotificationHandler extends AbstractNotificationHandler<SoContextService> {
    private final SctpChannel channel;

    public SctpNotificationHandler(SctpChannel channel) {
        this.channel = channel;
    }

    public HandlerResult handleNotification(AssociationChangeNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    public HandlerResult handleNotification(PeerAddressChangeNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    public HandlerResult handleNotification(SendFailedNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    public HandlerResult handleNotification(ShutdownNotification notification, SoContextService service) {
        this.fireEvent(notification);
        service.notifyChannelClose(this.channel.getChannelId(), true);
        return HandlerResult.RETURN;
    }

    private void fireEvent(Notification notification) {
        this.channel.fireUserEvent(SctpNotificationEvent.class, new SctpNotificationEvent(notification));
    }
}
