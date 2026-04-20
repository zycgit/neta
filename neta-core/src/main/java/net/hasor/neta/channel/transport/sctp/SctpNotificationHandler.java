package net.hasor.neta.channel.transport.sctp;
import com.sun.nio.sctp.*;
import net.hasor.neta.channel.SoContextService;
/**
 * Adapter that handles SCTP protocol notifications such as association changes and shutdowns.
 * <p>This class converts native SCTP notifications into network events inside the Neta framework.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
final class SctpNotificationHandler extends AbstractNotificationHandler<SoContextService> {
    private final SctpChannel channel;

    /**
     * Create a notification handler.
     * @param channel the owning channel
     */
    public SctpNotificationHandler(SctpChannel channel) {
        this.channel = channel;
    }

    /**
     * Handle an association change notification.
     * @param notification the SCTP notification object
     * @param service the channel context service
     * @return always returns continue
     */
    public HandlerResult handleNotification(AssociationChangeNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    /**
     * Handle a peer address change notification.
     * @param notification the SCTP notification object
     * @param service the channel context service
     * @return always returns continue
     */
    public HandlerResult handleNotification(PeerAddressChangeNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    /**
     * Handle a send-failed notification.
     * @param notification the SCTP notification object
     * @param service the channel context service
     * @return always returns continue
     */
    public HandlerResult handleNotification(SendFailedNotification notification, SoContextService service) {
        this.fireEvent(notification);
        return HandlerResult.CONTINUE;
    }

    /**
     * Handle a shutdown notification and trigger the channel-close event at the same time.
     * @param notification the SCTP notification object
     * @param service the channel context service
     * @return returns stop-processing
     */
    public HandlerResult handleNotification(ShutdownNotification notification, SoContextService service) {
        this.fireEvent(notification);
        service.notifyChannelClose(this.channel.getChannelId(), true);
        return HandlerResult.RETURN;
    }

    private void fireEvent(Notification notification) {
        this.channel.fireEvent(SctpNotificationEvent.class, new SctpNotificationEvent(notification));
    }
}
