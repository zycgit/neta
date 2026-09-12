/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
