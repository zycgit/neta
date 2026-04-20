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