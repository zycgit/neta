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
import java.net.SocketException;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Thrown when pipeline scheduling can no longer write data into the target queue.
 * <p>This exception acts as a channel-layer backpressure signal. {@link ProtoQueue} and
 * {@link ProtoSndQueue} themselves only report write failure through return values. Outer pipeline
 * scheduling logic can convert that failure into this exception when it discovers that the target
 * queue has no free slots.</p>
 * <p>Callers must decide how to react:</p>
 * <ul>
 *   <li><b>Wait and retry</b> if the overflow is temporary and the send can be retried after backoff.</li>
 *   <li><b>Drop</b> for protocols that tolerate loss, such as UDP.</li>
 *   <li><b>Close</b> for protocols that cannot tolerate data loss, by gracefully closing the channel and reporting the error.</li>
 * </ul>
 * <p>When no exception message is required, the singleton {@link #INSTANCE} can be used for
 * zero-allocation throwing.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoRcvQueue
 * @see ProtoSndQueue
 */
public class ProtoFullException extends SocketException {
    public static final ProtoFullException INSTANCE = new ProtoFullException();

    public ProtoFullException() {
    }

    public ProtoFullException(String msg) {
        super(msg);
    }
}