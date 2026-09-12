/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import net.hasor.neta.channel.ProtoBuilder;
import net.hasor.neta.channel.ProtoDuplex;
import net.hasor.neta.channel.ProtoInitializer;
/**
 * Builder used to define which handlers should be installed inside a single partition.
 * <p>When a partition duplexer is declared through
 * {@link ProtoBuilder#nextPartition(String, ProtoPartitionSelector, java.util.function.Consumer)},
 * the callback receives this interface. It describes how each partition instance should initialize
 * its own internal sub-pipeline after creation.</p>
 * <p>You can use it to configure partition policies, obtain the partition control handle, or supply
 * an existing {@link ProtoInitializer} as the template for the partition sub-pipeline.</p>
 */
public interface ProtoPartitionBuilder<RCV_UP, SND_DOWN> {
    /**
     * Return the control handle bound to the current partition duplexer.
     * <p>It can be used to configure external control logic related to partition creation and shutdown during build time.</p>
     */
    ProtoPartitionControl control();

    /**
     * Register a partition policy.
     * <p>The policy runs before a new partition is created or before new traffic first enters a
     * partition, deciding whether the trigger should be accepted, dropped, or rejected.</p>
     */
    ProtoPartitionBuilder<RCV_UP, SND_DOWN> policy(ProtoPartitionPolicy policy);

    /**
     * Use an existing initializer as the template for partition sub-pipelines.
     * <p>Whenever a partition key appears for the first time and a partition instance is created,
     * this initializer is used to build the internal handler chain of that partition.</p>
     */
    ProtoPartitionBuilder<RCV_UP, SND_DOWN> byInitializer(ProtoInitializer initializer);

    /**
     * Use an existing initializer as the template for the default partition sub-pipeline.
     * <p>When the selector does not return any {@link PartitionKey}, inbound data can still be
     * routed into this default sub-pipeline instead of being ignored or continuing in the parent
     * pipeline with the original unmatched behavior.</p>
     */
    ProtoPartitionBuilder<RCV_UP, SND_DOWN> byDefault(ProtoInitializer initializer);

    /**
     * Build the partition duplexer defined by the current builder.
     * @return built partition duplexer
     */
    ProtoDuplex<RCV_UP, RCV_UP, SND_DOWN, SND_DOWN> build();
}
