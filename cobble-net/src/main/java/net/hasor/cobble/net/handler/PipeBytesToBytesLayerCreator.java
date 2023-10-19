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
package net.hasor.cobble.net.handler;
import net.hasor.cobble.net.bytebuf.ByteBuf;

/**
 * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
 *
 * <ul>
 *  <li>RCV_UP is {@link ByteBuf}</li>
 *  <li>RCV_DOWN is {@link ByteBuf}</li>
 *  <li>SND_UP is {@link ByteBuf}</li>
 *  <li>SND_DOWN is {@link ByteBuf}</li>
 * </ul>
 *
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.cobble.net.handler.PipeBytesToBytesLayerCreator
 * @see net.hasor.cobble.net.handler.PipeBytesToMessageLayerCreator
 * @see net.hasor.cobble.net.handler.PipeMessageToBytesLayerCreator
 * @see net.hasor.cobble.net.handler.PipeMessageToMessageLayerCreator
 * @see net.hasor.cobble.net.handler.PipeLayerCreator
 * @see net.hasor.cobble.net.handler.PipeLayer
 */
public interface PipeBytesToBytesLayerCreator extends PipeLayerCreator<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {

}