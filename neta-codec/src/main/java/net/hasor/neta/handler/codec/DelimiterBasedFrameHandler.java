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
package net.hasor.neta.handler.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class DelimiterBasedFrameHandler implements PipeHandler<ByteBuf, ByteBuf> {
    @Override
    public PipeStatus doHandler(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) throws IOException {
        throw new UnsupportedEncodingException();
    }

    @Override
    public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHolder<ByteBuf, ByteBuf> eh) throws Throwable {
        throw new UnsupportedEncodingException();
    }
}