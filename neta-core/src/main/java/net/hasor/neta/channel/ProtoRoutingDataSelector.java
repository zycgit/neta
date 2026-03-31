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
 * Data-routing selector.
 * <p>{@link ProtoRoutingDuplexer} invokes it to decide which branch the current inbound data should
 * enter. The selector may base its decision on context state, the current inbound queue content,
 * or a combination of both.</p>
 * <p>In static routing mode, the first successfully returned branch is cached. In realtime routing
 * mode, selection is performed again whenever inbound messages arrive.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@FunctionalInterface
public interface ProtoRoutingDataSelector<RCV_UP, SND_DOWN> {
    /**
     * Compute the branch name to enter for the current round.
     * <p>This method is called during both the routing duplexer's {@code onActive} probe phase and
     * the RCV-side {@code onMessage} phase. When the selector returns a branch name, the routing
     * duplexer hands the current round to that branch. When it returns {@code null}, the current
     * round enters no branch.</p>
     * @param context current protocol context
     * @param rcvUp current inbound data queue; it is an empty queue during the {@code onActive} probe phase
     * @param sndDown send output queue of the current level; the selector may write control data when needed
     * @return registered branch name, or {@code null} when routing still cannot be determined for the current round
     */
    String route(ProtoContext context, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<SND_DOWN> sndDown);
}