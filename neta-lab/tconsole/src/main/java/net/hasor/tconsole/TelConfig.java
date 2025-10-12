/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.tconsole;
import java.util.concurrent.ThreadFactory;

/**
 * 命令生命周期定义
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2019年10月30日
 */
public class TelConfig {
    private ThreadFactory threadFactory;
    private int           workSize = 2;
    private boolean       silent   = false;

    public ThreadFactory getThreadFactory() {
        return this.threadFactory;
    }

    public void setThreadFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    public int getWorkSize() {
        return this.workSize;
    }

    public void setWorkSize(int workSize) {
        this.workSize = workSize;
    }

    public boolean isSilent() {
        return this.silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
    }
}