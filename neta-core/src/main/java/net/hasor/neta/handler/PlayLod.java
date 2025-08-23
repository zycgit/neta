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
package net.hasor.neta.handler;
import net.hasor.neta.channel.SoChannel;

/**
 * A message data in the message bus
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public interface PlayLod {
    /**
     * Returns the source channel associated with this operation result
     * @return The SoChannel instance that originated this operation
     */
    SoChannel<?> getSource();

    /**
     * Returns the data payload from the operation result
     * @return The actual data object, type depends on specific implementation
     */
    Object getData();

    /**
     * Returns any error that occurred during the operation
     * @return Throwable instance if an error occurred, null if operation succeeded
     */
    Throwable getError();

    /**
     * Indicates whether the operation completed successfully
     * @return true if operation succeeded without errors, false otherwise
     */
    boolean isSuccess();
}