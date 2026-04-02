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
 * Selector that decides which partition the current data should enter.
 * <p>When a message or event reaches {@link ProtoPartitionDuplexer}, the framework calls this
 * interface to compute a {@link PartitionKey}. Data with the same partition key enters the same
 * partition sub-pipeline, reusing the same partition context and handler state, while data with
 * different keys is isolated into different partition sub-pipelines.</p>
 * <p>The core responsibility of this interface is to provide a stable bucketing rule. For messages
 * or events that belong to the same logical partition, implementations should always return the
 * same {@link PartitionKey}.</p>
 * <p>Return value conventions are strict:</p>
 * <ul>
 * <li>returning a normal {@link PartitionKey} means the data explicitly enters that partition;</li>
 * <li>returning {@link PartitionKey#defaultKey()} means the data explicitly enters the default partition;</li>
 * <li>returning {@code null} means the selector does not match any partition for the current data.</li>
 * </ul>
 * <p>{@code null} is not treated as an alias of the default partition. In current partition
 * semantics, unmatched events continue along the parent pipeline, while unmatched messages are
 * passed through to the nodes after the partition duplexer.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-29
 */
@FunctionalInterface
public interface ProtoPartitionSelector {
    /**
     * Select a partition key from the current context and trigger data.
     * @param context current protocol context
     * @param kind trigger data kind
     * @param data current message or event object
     * @return target partition key, {@link PartitionKey#defaultKey()} for the explicit default
     * partition, or {@code null} when the current data should not enter any partition
     */
    PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data);
}