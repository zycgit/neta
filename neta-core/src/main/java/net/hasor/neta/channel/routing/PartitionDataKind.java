/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
/**
 * Identifies the kind of data currently being processed by a partition selector.
 * <p>{@link ProtoPartitionSelector} and {@link ProtoPartitionPolicy} use this enum to distinguish
 * protocol messages from events so they can apply different partition-routing or creation
 * strategies for each kind.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-31
 */
public enum PartitionDataKind {
    /** Protocol message data. */
    Message,

    /** Event data. */
    Event
}
