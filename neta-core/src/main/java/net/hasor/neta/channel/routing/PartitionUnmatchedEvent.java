/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.neta.channel.SoEventData;
import net.hasor.neta.channel.SoUtils;
/**
 * Event payload describing a batch of inbound messages that could not enter a newly created partition.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-02
 */
public class PartitionUnmatchedEvent implements SoEventData, Release {
    private final PartitionKey partitionKey;
    private final List<Object> messages;
    private final List<Object> readOnlyView;

    public PartitionUnmatchedEvent(PartitionKey partitionKey, List<?> messages) {
        this.partitionKey = partitionKey;
        this.messages = new ArrayList<Object>(messages);
        this.readOnlyView = Collections.unmodifiableList(this.messages);
    }

    public PartitionKey getPartitionKey() {
        return this.partitionKey;
    }

    public List<Object> getMessages() {
        return this.readOnlyView;
    }

    @Override
    public void release() {
        this.messages.forEach(SoUtils::release);
        this.messages.clear();
    }
}
