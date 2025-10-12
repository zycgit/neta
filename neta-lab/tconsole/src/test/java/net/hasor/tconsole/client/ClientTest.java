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
package net.hasor.tconsole.client;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static net.hasor.tconsole.TelOptions.ENDCODE_OF_SILENT;
import static net.hasor.tconsole.TelOptions.SILENT;

public class ClientTest {

    @Test
    public void attribute_test_1() throws IOException {
        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.start(new InetSocketAddress("127.0.0.1", 8082), s -> true);
            //
            client.setAttribute("abc", "cba");
            client.connectTo(new InetSocketAddress("127.0.0.1", 8082));
            //
            assert "cba".equals(client.sendCommand("get abc"));
        }
    }

    @Test
    public void attribute_test_2() throws IOException {
        try (TelClient client = new TelClient()) {
            try {
                client.setAttribute(SILENT, "cba");
                assert false;
            } catch (Exception e) {
                assert "the client does not support set SILENT attribute.".equals(e.getMessage());
            }

            try {
                client.setAttribute(ENDCODE_OF_SILENT, "cba");
                assert false;
            } catch (Exception e) {
                assert "the client does not support set ENDCODE_OF_SILENT attribute.".equals(e.getMessage());
            }
            //
            client.setAttribute("abc", "cba");
            assert true;

            try {
                client.sendCommand("abc");
                assert false;
            } catch (Exception e) {
                assert "the TelClient has been closed or not init.".equals(e.getMessage());
            }
        }
    }
}
