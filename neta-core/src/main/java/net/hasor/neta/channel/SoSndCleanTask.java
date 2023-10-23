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
import net.hasor.cobble.logging.Logger;

import java.util.List;

/**
 * clean task for snd.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCleanTask extends DefaultSoTask {
    private static final Logger          logger = Logger.getLogger(SoSndCleanTask.class);
    private final        long            channelID;
    private final        List<SoSndData> cleanTask1;
    private final        long            finishSize;
    private final        Throwable       finallyError;

    public SoSndCleanTask(long channelID, List<SoSndData> cleanTask1, long sndSize) {
        this.channelID = channelID;
        this.cleanTask1 = cleanTask1;
        this.finishSize = sndSize;
        this.finallyError = null; // finish
    }

    public SoSndCleanTask(long channelID, List<SoSndData> cleanTask1, long sndSize, Throwable e) {
        this.channelID = channelID;
        this.cleanTask1 = cleanTask1;
        this.finishSize = sndSize;
        this.finallyError = e; // error
    }

    @Override
    protected void doWork(int retryCnt) {
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
                logger.error("snd(" + this.channelID + ") CleanTask failed. " + e.getMessage(), e);
            }
        }

        finishTask();
    }
}