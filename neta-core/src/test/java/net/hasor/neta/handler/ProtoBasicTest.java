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
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
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
    public void nextTest_0() throws Throwable {
        ProtoInitializer initializer = (ctx) -> {
            return ProtoHelper.typed(Integer.class, Integer.class)           //
                    .nextDecoder("L1", new TransparentProtoHandler<>())//
                    .build();
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        channel.printStackTrace(new PrintStream(out));

        String data = "ChannelId  : 1,          Server(Active)\n" + //
                "Local Addr :                      vrt:1\n" + //
                "Remote Addr:                      vrt:1\n" + //
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