/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;
import java.io.IOException;
import net.hasor.neta.channel.*;
/**
 * SCTP channel implementation bound to the application-level protocol pipeline.
 * <p>This class wraps {@link SctpAsyncChannel} and manages the handler used for SCTP protocol
 * notifications.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SctpChannel extends NetChannel {
    private final SctpNotificationHandler notificationHandler;

    SctpChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, SctpAsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
        this.notificationHandler = new SctpNotificationHandler(this);
    }

    NetMonitor getNetMonitor() {
        return this.monitor;
    }

    SctpNotificationHandler notificationHandler() {
        return this.notificationHandler;
    }
}
