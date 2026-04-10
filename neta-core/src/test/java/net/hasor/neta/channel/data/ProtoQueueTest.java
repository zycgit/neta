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
package net.hasor.neta.channel.data;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoQueueTest {
    @Test
    public void offerTest01() {
        ProtoQueue<Object> queue = new ProtoQueue<>(10);
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;

        assert queue.offerMessage(1);
        assert queue.offerMessage(2);
        assert queue.offerMessage(3);
        assert queue.queueSize() == 3;
        assert queue.slotSize() == 7;

        assert queue.offerMessage(4);
        assert queue.offerMessage(Arrays.asList(5, 6));
        assert queue.queueSize() == 6;
        assert queue.slotSize() == 4;

        assert queue.offerMessage(Arrays.asList(7, 8));
        assert queue.queueSize() == 8;
        assert queue.slotSize() == 2;

        assert !queue.offerMessage(Arrays.asList(10, 11, 12, 13, 14, 15, 16, 17, 18, 19));
        assert queue.queueSize() == 8;
        assert queue.slotSize() == 2;

        assert queue.offerMessage(Arrays.asList(10, 11));
        assert queue.queueSize() == 10;
        assert queue.slotSize() == 0;

        List<Object> list = queue.takeMessage(100);
        assert list.size() == 10;
        assert (int) list.get(0) == 1;
        assert (int) list.get(1) == 2;
        assert (int) list.get(2) == 3;
        assert (int) list.get(3) == 4;
        assert (int) list.get(4) == 5;
        assert (int) list.get(5) == 6;
        assert (int) list.get(6) == 7;
        assert (int) list.get(7) == 8;
        assert (int) list.get(8) == 10;
        assert (int) list.get(9) == 11;

        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;
    }
}