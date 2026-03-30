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
package net.hasor.neta.channel;

/**
 * Imperative control handle exposed by {@link ProtoPartitionBuilder} and backed by
 * {@link ProtoPartitionDuplexer}.
 * <p>
 * The control handle is intentionally obtained during builder assembly instead of
 * {@link ProtoContext#context(Class)} lookup, so nested partition pipelines do not
 * introduce ambiguous runtime control lookup.
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-30
 */
public interface ProtoPartitionControl {
    /** Freezes creation of new partitions. Existing partitions continue to work. */
    void lockCreation();

    /** Resumes creation of new partitions after a previous freeze. */
    void unlockCreation();

    boolean isLockCreation();

    /** Returns {@code true} when the specified partition already exists. */
    boolean hasPartition(PartitionKey key);

    /** Closes and removes the specified partition if it exists. */
    boolean closePartition(PartitionKey key);

    /** Closes all active partitions immediately. */
    void closeAllPartitions();

    /** Returns the current number of active partitions. */
    int partitionSize();
}