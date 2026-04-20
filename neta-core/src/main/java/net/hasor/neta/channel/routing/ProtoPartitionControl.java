/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.routing;
import java.util.Collection;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoPartitionDuplexer;
/**
 * Partition control interface exposed by {@link ProtoPartitionBuilder} and backed by
 * {@link ProtoPartitionDuplexer}.
 * <p>This API is intentionally obtained during builder assembly rather than looked up at runtime
 * through {@link ProtoContext#context(Class)}, avoiding ambiguous runtime control lookup when
 * partition pipelines are nested.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-30
 */
public interface ProtoPartitionControl {
    /** Freeze the creation of new partitions. Existing partitions continue to work. */
    void lockCreation();

    /** Resume new-partition creation after it was previously frozen. */
    void unlockCreation();

    /** Return whether new-partition creation is currently blocked. */
    boolean isLockCreation();

    /** Return {@code true} when the specified partition already exists. */
    boolean contains(PartitionKey key);

    /** Return a snapshot of active partition keys. */
    Collection<PartitionKey> partitionKeys();

    /** Return the number of currently active partitions. */
    int partitionSize();

    //

    /** Request closing the specified partition after the current owner round drains pending output. */
    boolean requestClose(PartitionKey key);

    /** Request closing all active partitions after the current owner round drains pending output. */
    void requestCloseAll();

    /** Close and remove the specified partition immediately if it exists. */
    boolean closePartition(PartitionKey key);

    /** Close all active partitions immediately. */
    void closeAllPartitions();

    /**
     * Return whether the specified partition is currently in a closing state.
     * <p>As long as the partition has not been fully cleaned up and it has entered close or
     * request-close flow, this method returns {@code true}.</p>
     */
    boolean isClose(PartitionKey key);
}