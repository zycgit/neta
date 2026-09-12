/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.Test;

import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.TransparentProtoHandler;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoBasicTest extends AbstractStackTest {
    @Test
    public void nextTest_0() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", new TransparentProtoHandler<>())                     //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        channel.printStackTrace(new PrintStream(out));

        String data = "ChannelId  : 1,          Server(Active)\n" + //
                "Local Addr :                 vrt:bind:1\n" + //
                "Remote Addr:                 vrt:bind:1\n" + //
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
