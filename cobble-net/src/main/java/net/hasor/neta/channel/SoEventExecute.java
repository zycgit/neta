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
//package net.hasor.cobble.net;
//import net.hasor.cobble.concurrent.future.Future;
//
///**
// * 内部使用的低延迟任务分发执行器
// * @version : 2023-10-09
// * @author 赵永春 (zyc@hasor.net)
// */
//class SoEventExecute {
//    private TaskWheelBucket[] buckets; // 时间刻度槽位，总体表示 1秒
//
//    public <T> Future<T> submitSoTask(AbstractSoTask task, T result) {
//        this.context.closeChannel(this.channelID, "close");
//        this.finishTask();
//
//        return null;
//    }
//
//    class TaskWheelBucket {
//
//    }
//}