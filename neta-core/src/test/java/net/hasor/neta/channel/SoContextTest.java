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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoContextTest {
    @Test
    public void submitSoTask_Test() throws ExecutionException, InterruptedException {
        SoConfig config = new SoConfig();
        config.setNetlog(true);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(2);
        config.setTaskThreads(2);
        SoContextImpl context = new SoContextImpl(config, null);

        int round = 5000;
        boolean result = true;

        while (round > 0) {
            round--;
            AtomicInteger cnt = new AtomicInteger(0);

            Future<SoContextTest> future = context.submitSoTask(1, new SoDelayTask(0), this).onCompleted(f -> {
                cnt.incrementAndGet();
            }).onFailed(f -> {
                //
            });

            future.get();
            int i = cnt.get();
            if (i != 1) {
                result = false;
                break;
            }
        }

        assert result;
    }
}