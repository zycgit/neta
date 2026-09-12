/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;
import com.sun.nio.sctp.Notification;
import net.hasor.neta.channel.SoEventData;
/**
 * {@link SoEventData} wrapper for SCTP protocol notifications.
 * <p>In addition to ordinary payload messages, SCTP can emit protocol-level events such as
 * association state changes, peer address changes, send failures, and graceful shutdowns. This
 * class is used to propagate the raw {@link Notification} object through Neta's user-event
 * pipeline.
 * <p>Handlers can access the raw notification through {@link #getNotification()} and convert it to
 * a concrete SCTP notification subtype when needed.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see Notification
 * @see net.hasor.neta.channel.SoEvent
 */
public class SctpNotificationEvent implements SoEventData {
    private final Notification event;

    /**
     * Create an SCTP notification event.
     * @param event the raw SCTP notification object
     */
    public SctpNotificationEvent(Notification event) {
        this.event = event;
    }

    /**
     * Return the raw SCTP notification object.
     * @return the SCTP notification
     */
    public Notification getNotification() {
        return event;
    }
}
