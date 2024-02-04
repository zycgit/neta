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

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeEndpointTest extends AbstractPipeTest {

    @Test
    public void rcvHeapUpTest_1() {
        EmbeddedInitializer initializer = (ctx) -> {
            List<String> ignore = new ArrayList<>();
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextDecoder("COPY", pipConf, doCopyHandler("Dec1", ignore, ignore))       // rcv +1
                    .nextDecoder("NO_COPY", pipConf, doNotCopyHandler("Dec2", ignore, ignore)) // rcv +1
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.receive(1, 2); // in "COPY" rcvDown
        channel.receive(3, 4); // in pipline rcv up

        try {
            channel.receive(5, 6);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 0, require 2.");
        }

        assert channel.getPipeStatistical().heapUpOfRcv() == 4;
        assert channel.getPipeStatistical().heapUpOfRcv("COPY") == 2;
        assert channel.getPipeStatistical().heapUpOfRcv("NO_COPY") == 0;
        assert channel.getPipeStatistical().heapUpOfRcvRoot() == 2;
        assert channel.readRcv() == null;
    }

    @Test
    public void rcvHeapUpTest_2() {
        EmbeddedInitializer initializer = (ctx) -> {
            List<String> ignore = new ArrayList<>();
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextDecoder("COPY1", pipConf, doCopyHandler("Dec1", ignore, ignore)) // rcv +1
                    .nextDecoder("COPY2", pipConf, doCopyHandler("Dec2", ignore, ignore)) // rcv +1
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.receive(1, 2);
        channel.receive(3);
        channel.receive(4);
        channel.receive(5);

        assert channel.getPipeStatistical().heapUpOfRcv() == 0;
        assert channel.getPipeStatistical().heapUpOfRcv("COPY1") == 0;
        assert channel.getPipeStatistical().heapUpOfRcv("COPY2") == 0;
        assert channel.getPipeStatistical().heapUpOfRcvRoot() == 0;
        assert channel.getRcvSize() == 5;
    }

    @Test
    public void rcvHeapUpTest_3() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(1, 2);
        channel.receive(3, 4);
        channel.receive(5, 6, 7);
        channel.receive(8, 9, 10);
        assert channel.getRcvSize() == 10;
    }

    @Test
    public void rcvHeapUpTest_4() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        Exception e = new Exception("Error");
        channel.receiveError(e);

        assert channel.getRcvError() == e;
    }

    @Test
    public void sndHeapUpTest_1() {
        EmbeddedInitializer initializer = (ctx) -> {
            List<String> ignore = new ArrayList<>();
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextEncoder("COPY", pipConf, doCopyHandler("Enc1", ignore, ignore))       // snd +1
                    .nextEncoder("NO_COPY", pipConf, doNotCopyHandler("Enc2", ignore, ignore)) // snd +1
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.send(1, 2); // in "COPY" rcvDown
        try {
            channel.send(3, 4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 0, require 2.");
        }

        assert channel.getPipeStatistical().heapUpOfSnd() == 2;
        assert channel.getPipeStatistical().heapUpOfRcv("COPY") == 0;
        assert channel.getPipeStatistical().heapUpOfRcv("NO_COPY") == 0;
        assert channel.getPipeStatistical().heapUpOfSndRoot() == 2;
        assert channel.readSnd() == null;
    }

    @Test
    public void sndHeapUpTest_2() {
        EmbeddedInitializer initializer = (ctx) -> {
            List<String> ignore = new ArrayList<>();
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextEncoder("COPY1", pipConf, doCopyHandler("Dec1", ignore, ignore)) //
                    .nextEncoder("COPY2", pipConf, doCopyHandler("Dec2", ignore, ignore)) //
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.send(1, 2);
        channel.send(3);
        channel.send(4);
        channel.send(5);

        assert channel.getPipeStatistical().heapUpOfSnd() == 0;
        assert channel.getPipeStatistical().heapUpOfSnd("COPY1") == 0;
        assert channel.getPipeStatistical().heapUpOfSnd("COPY2") == 0;
        assert channel.getPipeStatistical().heapUpOfSndRoot() == 0;
        assert channel.getSndSize() == 5;
    }

    @Test
    public void sndHeapUpTest_3() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.send(1, 2);
        channel.send(3, 4);
        channel.send(5, 6, 7);
        channel.send(8, 9, 10);
        assert channel.getSndSize() == 10;
    }

    @Test
    public void sndHeapUpTest_4() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        Exception e = new Exception("Error");
        channel.sendError(e);

        assert channel.getSndError() == e;
    }

    @Test
    public void rcvToSendTest_1() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doPipeLayer(true, false)).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(123);
        assert channel.readRcv().equals(123);
        assert channel.readSnd().equals(888);
    }

    @Test
    public void rcvToSendTest_2() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doPipeLayer(false, true)).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(123);
        assert channel.readRcv().equals(123);
        assert channel.readSnd().equals(999);
    }

    @Test
    public void rcvToSendTest_3() {
        EmbeddedInitializer initializer = (ctx) -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doPipeLayer(true, true)).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(123);
        assert channel.readRcv().equals(123);
        assert channel.readSnd().equals(888);
        assert channel.readSnd().equals(999);
    }
}