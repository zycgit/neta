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
package net.hasor.neta.channel;
import java.io.Closeable;
import java.io.PrintStream;
import java.net.SocketAddress;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Internal utility methods for the Neta channel layer.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoUtils {
    private static final Logger logger = Logger.getLogger(SoUtils.class);

    /** Release an owned object according to Neta ownership conventions. */
    public static void release(Object item) {
        if (item == null) {
            return;
        }

        try {
            if (item instanceof ReferenceHolder) {
                ((ReferenceHolder) item).release();
            } else if (item instanceof Release) {
                ((Release) item).release();
            } else if (item instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) item);
            }
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }

    /** Build a {@link SoConnectTimeoutException} for the remote address of the specified channel. */
    public static SoConnectTimeoutException newConnectTimeout(boolean isRcv, long channelId, SoContextService context, Throwable e) {
        SocketAddress address = context.getRemoteAddress(channelId);
        String errorMsg = (isRcv ? "rcv(" : "snd(") + channelId + ") Connection timed out: " + address;
        SoConnectTimeoutException cause = new SoConnectTimeoutException(errorMsg);
        cause.setStackTrace(e.getStackTrace());
        return cause;
    }

    /**
     * Print the current {@link ProtoStackChain} state and backtrace information to the specified output stream.
     * @param s {@link PrintStream} used for output
     */
    public static void printStackTrace(PrintStream s, SoChannel<?> channel, ProtoStackChain protoStack) {
        String body = protoStack == null ? "--- There is no ProtoStackChain ---" : protoStack.toString();
        int len = body.split("\n")[0].length();
        String ctitle = "ChannelId  : " + channel.getChannelId() + ",";
        String status = channel.isClient() ? "Client" : "Server";
        status = status + (channel.isClose() ? "(Closed)" : "(Active)");
        s.println(StringUtils.rightPad(ctitle, len - status.length(), " ") + status);

        String laddrTitle = "Local Addr :";
        String laddrValue = channel.getLocalAddr().toString();
        s.println(StringUtils.rightPad(laddrTitle, len - laddrValue.length(), " ") + laddrValue);

        String raddrTitle = "Remote Addr:";
        String raddrValue = channel.getRemoteAddr().toString();
        s.println(StringUtils.rightPad(raddrTitle, len - raddrValue.length(), " ") + raddrValue);

        s.println(body);
    }

    static String generateName(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        String decName = Integer.toHexString(System.identityHashCode(decoder));
        String encName = Integer.toHexString(System.identityHashCode(encoder));
        return String.format("%s/%s", decName, encName);
    }

    static String generateName(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        return Integer.toHexString(System.identityHashCode(duplexer));
    }

    static String generateName(ProtoHandler<?, ?> encoder) {
        return Integer.toHexString(System.identityHashCode(encoder));
    }
}