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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Application protocol stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface PipeStack<OUT> {
    /**
     * when connected.
     * @param pipeContext pipeContext
     */
    default void init(PipeContext pipeContext) throws Throwable {
    }

    /**
     * Processing received data
     * @param pipeContext pipeContext
     * @param rcvData received data
     * @return The return {@link ByteBuf} or Message, well be send to remote.
     */
    OUT[] rcvLayer(PipeContext pipeContext, String pipeName, Object[] rcvData) throws Throwable;

    /**
     * Errors from the network layer.
     * @param pipeContext pipeContext
     * @param rcvError network error.
     */
    OUT[] rcvError(PipeContext pipeContext, String pipeName, Throwable rcvError) throws Throwable;

    /**
     * Trigger sending data
     * @param pipeContext pipeContext
     * @param sndData send data
     * @return The return {@link ByteBuf} or Message, well be send to remote.
     */
    OUT[] sndLayer(PipeContext pipeContext, String pipeName, Object[] sndData) throws Throwable;

    /**
     * Errors from the network layer.
     * @param pipeContext pipeContext
     * @param sndError network error.
     */
    OUT[] sndError(PipeContext pipeContext, String pipeName, Throwable sndError) throws Throwable;

    /**
     * before close.
     * @param pipeContext pipeContext
     */
    default void release(PipeContext pipeContext) {
    }
}