/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoPartitionDuplex;

/**
 * Policy hook consulted by the partition duplexer before creating a new partition.
 * <p>After {@link ProtoPartitionDuplex} resolves a partition key through
 * {@link ProtoPartitionSelector}, it calls this interface if no partition instance exists yet for
 * that key, and then decides whether to create the corresponding partition sub-pipeline using the
 * default flow.</p>
 * <p>Existing partitions are reused directly. Later messages or events enter the existing partition
 * sub-pipeline without invoking the policy again just because the same partition is hit a second
 * time.</p>
 * <p>This hook is suitable for rules such as limiting partition count, rejecting abnormal keys, or
 * dropping traffic that would create a new partition in specific situations.</p>
 * <p>{@link ReceivePolicy#Accept} continues the default partition flow,
 * {@link ReceivePolicy#Drop} silently discards the current trigger, and
 * {@link ReceivePolicy#Reject} rejects the trigger and lets the framework treat it as an error.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-30
 */
@FunctionalInterface
public interface ProtoPartitionPolicy {
    enum ReceivePolicy {
        /** Accept the trigger and continue the default partition flow. */
        Accept,
        /** Drop the trigger without creating a partition or continuing dispatch. */
        Drop,
        /** Reject the trigger and let the upper layer handle it as an error. */
        Reject
    }

    /**
     * Decide how the current partition trigger should be handled before a new partition instance is created.
     * <p>This method is called only when the target partition does not exist yet. If a partition for
     * {@code key} already exists, the framework reuses it directly and does not invoke the policy.</p>
     * @param context current protocol context
     * @param control current partition control handle
     * @param key partition key matched by the current trigger
     * @param kind trigger data kind
     * @param data current message or event object
     * @return receive policy to apply to this trigger
     * @throws Throwable thrown when policy evaluation fails
     */
    ReceivePolicy newPartition(ProtoContext context, ProtoPartitionControl control,//
            PartitionKey key, PartitionDataKind kind, Object data) throws Throwable;
}
