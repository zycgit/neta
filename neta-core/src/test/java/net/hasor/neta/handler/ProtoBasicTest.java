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
import net.hasor.neta.codec.TransparentProtoHandler;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoBasicTest extends AbstractStackTest {
    @Test
    public void nextTest_0() {
        EmbeddedInitializer initializer = (ctx) -> {
            return ProtoHelper.embedded(Integer.class, Integer.class)        //
                    .nextDecoder("L1", new TransparentProtoHandler<>())//
                    .build();
        };

        EmbeddedSoContext.resetChannelID(0);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        channel.printStackTrace(new PrintStream(out));

        String data = "ChannelID  : 1,          Server(Active)\n" + //
                "Local Addr :                   embedded\n" + //
                "Remote Addr:                   embedded\n" + //
                "┏━━━━━━━━━━━━━━━━━━━━ ↓ 0/500+ (SND) ━┓\n" + //
                "┃ L1 [↑ 0/500+,       ↓ 0/500+      ] ┃\n" + //
                "┗━━━━ ↑ 0/500+ (RCV) ━━━━━━━━━━━━━━━━━┛\n";

        if (!out.toString().trim().equals(data.trim())) {
            System.out.println(out.toString().trim());
            System.out.println("--");
            System.out.println(data.trim());
            assert false;
        } else {
            assert true;
        }
    }
}