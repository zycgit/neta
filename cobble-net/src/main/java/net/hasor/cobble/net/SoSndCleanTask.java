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
import java.util.List;

/**
 * 当网络发送完毕执行 SoSndData 的 finish 方法.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoSndCleanTask extends AbstractSoTask {
    private final List<SoSndData> taskLists;

    public SoSndCleanTask(List<SoSndData> taskLists) {
        this.taskLists = taskLists;
    }

    @Override
    public void run() {
        for (SoSndData sndData : this.taskLists) {
            sndData.finish();
        }
    }
}
