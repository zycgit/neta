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
 * Represents a message payload in the message bus system.
 * Encapsulates the data, error, and source channel information for a message.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public interface PlayLoad {
    /**
     * Creates a successful payload with the specified source channel and data.
     * @param source the originating SoChannel
     * @param data the message data
     * @return a new PlayLoad instance containing the data
     */
    static PlayLoad of(SoChannel<?> source, Object data, boolean inbound, boolean outbound) {
        return new PlayLoadObject(data, null, source, inbound, outbound);
    }

    /**
     * Creates an error payload with the specified source channel and error.
     * @param source the originating SoChannel
     * @param data the error information
     * @return a new PlayLoad instance containing the error
     */
    static PlayLoad ofError(SoChannel<?> source, Throwable data, boolean inbound, boolean outbound) {
        return new PlayLoadObject(null, data, source, inbound, outbound);
    }

    /**
     * Gets the source channel from which this payload originated.
     * @return the source SoChannel instance
     */
    SoChannel<?> getSource();

    /**
     * Gets the data contained in this payload.
     * @return the payload data object, or null if an error occurred
     */
    Object getData();

    /**
     * Gets the error associated with this payload, if any.
     * @return the Throwable error, or null if the operation was successful
     */
    Throwable getError();

    /**
     * Indicates whether the payload represents a successful operation.
     * @return true if no error is present, false otherwise
     */
    boolean isSuccess();

    /**
     * Indicates whether the payload is inbound (received).
     * @return true if the payload is inbound, false otherwise
     */
    boolean isInbound();

    /**
     * Indicates whether the payload is outbound (sent).
     * @return true if the payload is outbound, false otherwise
     */
    boolean isOutbound();
}