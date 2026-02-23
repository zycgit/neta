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
package net.hasor.tconsole.launcher;
import java.io.*;
import java.util.Arrays;
import java.util.LinkedList;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.tconsole.launcher.hosts.HostTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

public class HostServerTest {
    protected void threeCommandToWriter(Writer writer) {
        LinkedList<String> preCommand = new LinkedList<String>() {{
            this.addAll(Arrays.asList("help", "test", "exit"));
        }};
        while (!preCommand.isEmpty()) {
            try {
                Thread.sleep(500);
                String pop = preCommand.pop();
                if (StringUtils.isNotBlank(pop)) {
                    writer.write(pop + "\n");
                }
            } catch (Exception e) { /**/ }
        }
    }

    @Test
    public void pip_host_test_1() throws IOException {
        HostTelService telService = new HostTelService();
        telService.addCommand("test", new TestExecutor());

        PipedWriter dataIn = new PipedWriter();
        StringWriter dataOut = new StringWriter();
        telService.startAt(true, new PipedReader(dataIn), dataOut);

        // .执行3条命令(每 500ms 产生一条命令到 piped)
        this.threeCommandToWriter(dataIn);
        ThreadUtils.sleep(500);

        String string = dataOut.toString();
        assert string.contains("use the 'exit' or 'quit' out of the console."); // 非静默模式，所以有欢迎信息
        assert string.contains(" - test  hello help.");                         // help 命令
        assert string.contains("{\"args\":\"\",\"name\":\"test\",\"body\":\"\"}");            // test 命令
        assert string.contains("bye.");                                         // exit 命令
        assert !telService.isSilent();

        telService.close();
    }

    public static void main(String[] args) {
        HostTelService telService = new HostTelService();
        telService.addCommand("test", new TestExecutor());

        telService.startAt(false, new InputStreamReader(System.in), new PrintWriter(System.out));
    }
}