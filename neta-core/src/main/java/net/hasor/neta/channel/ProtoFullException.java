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

/**
 * Thrown when a pipeline queue cannot accept more items.
 * <p>This exception is the channel layer's backpressure signal. It can be raised while feeding
 * either the receive path or the send path whenever the target {@link ProtoQueue} has no free
 * slots left. The caller decides how to react:
 * <ul>
 *   <li><b>Wait and retry</b> – if the overflow is transient, back off briefly and re-deliver.</li>
 *   <li><b>Discard</b> – for lossy protocols (e.g., UDP) where dropping is acceptable.</li>
 *   <li><b>Close</b> – for protocols that cannot tolerate data loss, close the channel cleanly
 *       and report the error to the application.</li>
 * </ul>
 * <p>The singleton {@link #INSTANCE} can be used for allocation-free throws when the exception
 * message is not needed.
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