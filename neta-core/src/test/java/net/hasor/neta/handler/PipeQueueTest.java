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
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeQueueTest {
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

        List<Object> list = queue.takeMessage(100);
        assert list.size() == 10;
        assert (int) list.get(0) == 4;
        assert (int) list.get(1) == 5;
        assert (int) list.get(2) == 6;
        assert (int) list.get(3) == 7;
        assert (int) list.get(4) == 8;
        assert (int) list.get(5) == 10;
        assert (int) list.get(6) == 11;
        assert (int) list.get(7) == 12;
        assert (int) list.get(8) == 13;
        assert (int) list.get(9) == 14;

        assert queue.queueSize() == 0;
        assert queue.slotSize() == 0;

        queue.rcvReset();
        assert queue.queueSize() == 10;
        assert queue.slotSize() == 0;

        List<Object> list2 = queue.takeMessage(100);
        assert list2.size() == 10;
        assert (int) list2.get(0) == 4;
        assert (int) list2.get(1) == 5;
        assert (int) list2.get(2) == 6;
        assert (int) list2.get(3) == 7;
        assert (int) list2.get(4) == 8;
        assert (int) list2.get(5) == 10;
        assert (int) list2.get(6) == 11;
        assert (int) list2.get(7) == 12;
        assert (int) list2.get(8) == 13;
        assert (int) list2.get(9) == 14;

        queue.rcvSubmit();
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;
    }
}