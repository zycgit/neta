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
 * Policy hook invoked by {@link ProtoPartitionDuplexer} before its default partition routing logic.
 * <p>
 * Returning {@code true} means the trigger has been consumed by the policy and the duplexer
 * should stop its own default processing for that trigger. Returning {@code false} keeps the
 * normal partition selection and dispatch path.
 * </p>
 * <p>
 * The {@code trigger} parameter is the raw message when {@code triggerKind == MESSAGE}, and the
 * raw {@link SoUserEvent} when {@code triggerKind == USER_EVENT}.
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-30
 */
@FunctionalInterface
public interface ProtoPartitionPolicy {
    enum ReceivePolicy {
        Accept,
        Drop,
        Reject
    }

    ReceivePolicy newPartition(ProtoContext context, ProtoPartitionControl control,//
            PartitionKey key, PartitionDataKind kind, Object data) throws Throwable;
}