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
 * Handle a one-way stream of {@link ByteBuf} to Message.
 *
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.neta.handler.PipeBytesToBytesHandler
 * @see net.hasor.neta.handler.PipeBytesToMessageHandler
 * @see net.hasor.neta.handler.PipeMessageToBytesHandler
 * @see net.hasor.neta.handler.PipeMessageToMessageHandler
 * @see net.hasor.neta.handler.PipeHandler
 */
@FunctionalInterface
public interface PipeBytesToMessageHandler<OUT> extends PipeHandler<ByteBuf, PipeSndQueue<OUT>> {

}