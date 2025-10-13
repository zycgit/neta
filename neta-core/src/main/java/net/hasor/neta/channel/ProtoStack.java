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
public interface ProtoStack<OUT> {

    /**
     * Returns the number of available ProtoStack receive slots, The maximum value is Integer.MAX_VALUE
     */
    int getRcvSlotSize();

    /**
     * Returns the number of ProtoStack send slots available, The maximum value is Integer.MAX_VALUE
     */
    int getSndSlotSize();

    /**
     * when init, before onActive
     * @param protoCtx protoCtx
     */
    void onInit(ProtoContext protoCtx) throws Throwable;

    /**
     * when connected, before any rcv/snd
     * @param protoCtx protoCtx
     */
    void onActive(ProtoContext protoCtx) throws Throwable;

    /**
     * Processing received data
     * @param protoCtx protoCtx
     * @param rcvData received data
     * @return The return {@link ByteBuf} or Message, well be send to remote.
     */
    OUT[] onRcvMessage(ProtoContext protoCtx, String stackName, Object[] rcvData) throws Throwable;

    /**
     * Errors from the network layer.
     * @param protoCtx protoCtx
     * @param rcvError network error.
     */
    OUT[] onRcvError(ProtoContext protoCtx, String stackName, Throwable rcvError) throws Throwable;

    /**
     * Trigger sending data
     * @param protoCtx protoCtx
     * @param sndData send data
     * @return The return {@link ByteBuf} or Message, well be send to remote.
     */
    OUT[] onSndMessage(ProtoContext protoCtx, String stackName, Object[] sndData) throws Throwable;

    /**
     * Errors from the network layer.
     * @param protoCtx protoCtx
     * @param sndError network error.
     */
    OUT[] onSndError(ProtoContext protoCtx, String stackName, Throwable sndError) throws Throwable;

    /**
     * event from the network layer.
     * @param protoCtx protoCtx
     */
    void onUserEvent(ProtoContext protoCtx, SoUserEvent event) throws Throwable;

    /**
     * before close.
     * @param protoCtx protoCtx
     */
    void onClose(ProtoContext protoCtx);
}