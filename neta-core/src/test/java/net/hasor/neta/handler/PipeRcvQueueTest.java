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
import org.junit.Test;

import java.util.Arrays;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeRcvQueueTest {
    @Test
    public void offerTest01() {
        PipeQueue<Object> queue = new PipeQueue<>(10);
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;

        assert queue.offerMessage(1);
        assert queue.offerMessage(2);
        assert queue.offerMessage(3);
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 7;

        queue.sndReset();
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;

        assert queue.offerMessage(4);
        assert queue.offerMessage(Arrays.asList(5, 6)) == 2;
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 7;

        queue.rcvSubmit();
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 7;

        queue.sndSubmit();
        assert queue.queueSize() == 3;
        assert queue.slotSize() == 7;

        assert queue.offerMessage(Arrays.asList(7, 8)) == 2;
        assert queue.queueSize() == 3;
        assert queue.slotSize() == 5;

        assert queue.offerMessage(Arrays.asList(10, 11, 12, 13, 14, 15, 16, 17, 18, 19)) == 5;
        assert queue.queueSize() == 3;
        assert queue.slotSize() == 0;

        queue.sndSubmit();
        assert queue.queueSize() == 10;
        assert queue.slotSize() == 0;

        Object[] list = queue.takeMessage(100);
        assert list.length == 10;
        assert (int) list[0] == 4;
        assert (int) list[1] == 5;
        assert (int) list[2] == 6;
        assert (int) list[3] == 7;
        assert (int) list[4] == 8;
        assert (int) list[5] == 10;
        assert (int) list[6] == 11;
        assert (int) list[7] == 12;
        assert (int) list[8] == 13;
        assert (int) list[9] == 14;

        assert queue.queueSize() == 0;
        assert queue.slotSize() == 0;

        queue.rcvReset();
        assert queue.queueSize() == 10;
        assert queue.slotSize() == 0;

        Object[] list2 = queue.takeMessage(100);
        assert list2.length == 10;
        assert (int) list2[0] == 4;
        assert (int) list2[1] == 5;
        assert (int) list2[2] == 6;
        assert (int) list2[3] == 7;
        assert (int) list2[4] == 8;
        assert (int) list2[5] == 10;
        assert (int) list2[6] == 11;
        assert (int) list2[7] == 12;
        assert (int) list2[8] == 13;
        assert (int) list2[9] == 14;

        queue.rcvSubmit();
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;
    }
}