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
package net.hasor.cobble.net;
import net.hasor.cobble.logging.Logger;

import java.util.List;

/**
 * 当网络发送完毕执行 SoSndData 的 finish 方法.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoSndCleanTask extends AbstractSoTask {
    private static final Logger          logger = Logger.getLogger(SoSndCleanTask.class);
    private final        long            channelID;
    private final        List<SoSndData> cleanTask1;
    private final        Runnable        cleanTask2;
    private final        long            finishSize;
    private final        Throwable       finallyError;

    public SoSndCleanTask(long channelID, List<SoSndData> cleanTask1) {
        this.channelID = channelID;
        this.cleanTask1 = cleanTask1;
        this.cleanTask2 = null;
        this.finishSize = Long.MAX_VALUE;
        this.finallyError = null;
    }

    public SoSndCleanTask(long channelID, List<SoSndData> cleanTask1, Runnable cleanTask2, long sndSize) {
        this.channelID = channelID;
        this.cleanTask1 = cleanTask1;
        this.cleanTask2 = cleanTask2;
        this.finishSize = sndSize;
        this.finallyError = null; // finish
    }

    public SoSndCleanTask(long channelID, List<SoSndData> cleanTask1, Runnable cleanTask2, long sndSize, Throwable e) {
        this.channelID = channelID;
        this.cleanTask1 = cleanTask1;
        this.cleanTask2 = cleanTask2;
        this.finishSize = sndSize;
        this.finallyError = e; // error
    }

    @Override
    public void run() {
        long size = 0;
        for (SoSndData sndData : this.cleanTask1) {
            try {
                size += sndData.getDataSize();
                if (this.finishSize >= size) {
                    sndData.completed();
                } else if (this.finallyError != null) {
                    sndData.failed(this.finallyError);
                }
            } catch (Exception e) {
                logger.error("ERROR: CleanTask (" + this.channelID + ") " + e.getMessage(), e);
            }
        }

        if (this.cleanTask2 != null) {
            try {
                this.cleanTask2.run();
            } catch (Exception e) {
                logger.error("ERROR: CleanTask (" + this.channelID + ") " + e.getMessage(), e);
            }
        }

        finishTask();
    }
}
