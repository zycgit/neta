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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
 *
 * <ul>
 *  <li>RCV_UP is {@link ByteBuf}</li>
 *  <li>RCV_DOWN is Message</li>
 *  <li>SND_UP is Message</li>
 *  <li>SND_DOWN is {@link ByteBuf}</li>
 * </ul>
 *
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.neta.handler.PipeBytesToBytesLayer
 * @see net.hasor.neta.handler.PipeBytesToMessageLayer
 * @see net.hasor.neta.handler.PipeMessageToBytesLayer
 * @see net.hasor.neta.handler.PipeMessageToMessageLayer
 * @see net.hasor.neta.handler.PipeLayer
 */
@FunctionalInterface
public interface PipeBytesToMessageLayer<RCV_DOWN, SND_UP> extends PipeLayer<ByteBuf, PipeSndQueue<RCV_DOWN>, PipeRcvQueue<SND_UP>, ByteBuf> {

}