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
package net.hasor.cobble.net.channel;

import java.io.IOException;

/**
 * PipeLayer is like a dual carriageway, The data flow direction is identified by the isRcv parameter.
 *
 * There are two pipeline handlers that combine in opposite directions
 * <pre>
 *                    /-------------\
 *  RCV  ...(UP)   -> |             | -> ...(DOWN) -> RCV
 *                    |  PipeLayer  |
 *  SND  ...(DOWN) <- |             | <- ...(UP)   <- SND
 *                    \-------------/
 * </pre>
 *
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeHandler
 * @see net.hasor.cobble.net.channel.PipeConfig
 */
public interface PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {

    /**
     * Initialize the protocol stack
     */
    void initLayer(PipeContext pipeContext) throws Exception;

    /**
     * process data the protocol stack, param isRcv = true is RCV_UP to RCV_DOWN
     */
    PipeStatus doLayer(PipeContext context, boolean isRcv,//
            RCV_UP rcvUpstream, RCV_DOWN rcvDownstream, //
            SND_UP sndUpstream, SND_DOWN sndDownstream) throws IOException;

    /**
     * release protocol stack
     */
    void releaseLayer(PipeContext pipeContext);
}