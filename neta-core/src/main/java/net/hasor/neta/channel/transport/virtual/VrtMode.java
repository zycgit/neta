/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.virtual;
/**
 * Role marker used by virtual channels.
 * <p>{@link #Client} and {@link #Server} are applied to the concrete {@link VrtChannel} views
 * exposed after a virtual link is established, while {@link #Default} is the neutral preset used
 * during configuration and connect-mode client creation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public enum VrtMode {
    Default,
    Server,
    Client
}
