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
package net.hasor.tconsole.launcher.telnet;
import java.io.IOException;
import java.io.Writer;
import net.hasor.neta.channel.NetChannel;

/**
 * Handles writer
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
class SocketWriter extends Writer {
    private final NetChannel    channel;
    private final StringBuilder buffer;
    private       boolean       closeRequested;

    SocketWriter(NetChannel channel) {
        this.channel = channel;
        this.buffer = new StringBuilder();
    }

    public boolean isClose() {
        return this.closeRequested || this.channel.isClose();
    }

    public boolean isCloseRequested() {
        return this.closeRequested;
    }

    public String drainMessage() {
        String message = this.buffer.toString();
        this.buffer.setLength(0);
        return message;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        this.buffer.append(cbuf, off, len);
    }

    @Override
    public void flush() throws IOException {

    }

    @Override
    public void close() throws IOException {
        this.closeRequested = true;
    }
}
