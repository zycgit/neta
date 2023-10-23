///*
// * Copyright 2008-2009 the original author or authors.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package net.hasor.neta.handler;
//import net.hasor.cobble.concurrent.future.BasicFuture;
//import net.hasor.cobble.concurrent.future.Future;
//import net.hasor.neta.channel.SoChannel;
//
///**
// * Base class for {@link SoChannel} implementations that are used in an embedded fashion.
// * @version : 2023-09-24
// * @author 赵永春 (zyc@hasor.net)
// */
//public class EmbeddedChannel implements SoChannel<EmbeddedChannel> {
//    @Override
//    public long getChannelID() {
//        return 0;
//    }
//
//    @Override
//    public long getCreatedTime() {
//        return 0;
//    }
//
//    @Override
//    public long getLastActiveTime() {
//        return 0;
//    }
//
//    @Override
//    public boolean isListen() {
//        return false;
//    }
//
//    @Override
//    public boolean isServer() {
//        return false;
//    }
//
//    @Override
//    public boolean isClient() {
//        return false;
//    }
//
//    @Override
//    public Future<EmbeddedChannel> close() {
//        return new BasicFuture<>(this);
//    }
//
//    @Override
//    public Future<EmbeddedChannel> closeNow() {
//        return new BasicFuture<>(this);
//    }
//
//    @Override
//    public boolean isClose() {
//        return false;
//    }
//}