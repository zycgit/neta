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
 * Marks a channel as a sub-channel of a parent channel.
 * <p>
 * Sub-channels have their lifecycle tied to their parent: when the parent
 * is closed, all sub-channels are closed as well. During shutdown, the
 * framework closes parent channels first so that sub-channels are cleaned
 * up in the correct order.
 * @author 赵永春 (zyc@hasor.net)
 * @see NetChannel
 */
public interface SoSubChannel {
    /**
     * Returns the parent channel that owns this sub-channel.
     * @return the parent channel, never {@code null}
     */
    SoChannel<?> getParent();
}
