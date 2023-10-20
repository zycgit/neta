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
/**
 * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
 *
 * <ul>
 *  <li>RCV_UP is Message</li>
 *  <li>RCV_DOWN is Message</li>
 *  <li>SND_UP is Message</li>
 *  <li>SND_DOWN is Message</li>
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
@FunctionalInterface
public interface PipeMessageToMessageLayerCreator<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> extends //
        PipeLayerCreator<PipeRcvQueue<RCV_UP>, PipeSndQueue<RCV_DOWN>, PipeRcvQueue<SND_UP>, PipeSndQueue<SND_DOWN>> {
}