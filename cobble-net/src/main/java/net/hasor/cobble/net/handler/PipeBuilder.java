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
package net.hasor.cobble.net.handler;
import net.hasor.cobble.net.bytebuf.ByteBuf;
import net.hasor.cobble.net.channel.PipeLayerStack;

/**
 * Application stack builder
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
public interface PipeBuilder {
    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf}</li>
     *  <li>SND_UP is {@link ByteBuf}</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @throws NullPointerException if the specified handler is {@code null}
     */
    PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeBytesToBytesLayer pipeLayer);

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is Message</li>
     *  <li>SND_UP is Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeBytesToMessageLayer<RCV_DOWN, SND_UP> pipeLayer);

    /**
     * using decoder and encoder to combined for duplex.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf}</li>
     *  <li>SND_UP is {@link ByteBuf}</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeBytesToBytesHandler decoder, PipeBytesToBytesHandler encoder);

    /**
     * using decoder and encoder to combined for duplex.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is Message</li>
     *  <li>SND_UP is Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeBytesToMessageHandler<RCV_DOWN> decoder, PipeMessageToBytesHandler<SND_UP> encoder);

    interface PipeStackBuilder<RCV_UP, SND_DOWN> {

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf}</li>
         *  <li>RCV_DOWN is {@link ByteBuf}</li>
         *  <li>SND_UP is {@link ByteBuf}</li>
         *  <li>SND_DOWN is {@link ByteBuf}</li>
         * </ul>
         *
         * @throws NullPointerException if the specified handler is {@code null}
         */
        PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeBytesToBytesLayer pipeLayer);

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf}</li>
         *  <li>RCV_DOWN is Message</li>
         *  <li>SND_UP is Message</li>
         *  <li>SND_DOWN is {@link ByteBuf}</li>
         * </ul>
         *
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeBytesToMessageLayer<RCV_DOWN, SND_UP> pipeLayer);

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is Message</li>
         *  <li>RCV_DOWN is Message</li>
         *  <li>SND_UP is Message</li>
         *  <li>SND_DOWN is Message</li>
         * </ul>
         *
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeMessageToMessageLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer);

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf}</li>
         *  <li>SND_UP is {@link ByteBuf}</li>
         *  <li>SND_DOWN is Message</li>
         * </ul>
         *
         * @throws NullPointerException if the specified handler is {@code null}
         */
        PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeMessageToBytesLayer<RCV_UP, SND_DOWN> pipeLayer);

        /**
         * using decoder and encoder to combined for duplex.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf}</li>
         *  <li>RCV_DOWN is {@link ByteBuf}</li>
         *  <li>SND_UP is {@link ByteBuf}</li>
         *  <li>SND_DOWN is {@link ByteBuf}</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeBytesToBytesHandler decoder, PipeBytesToBytesHandler encoder);

        /**
         * using decoder and encoder to combined for duplex.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf}</li>
         *  <li>RCV_DOWN is Message</li>
         *  <li>SND_UP is Message</li>
         *  <li>SND_DOWN is {@link ByteBuf}</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeBytesToMessageHandler<RCV_DOWN> decoder, PipeMessageToBytesHandler<SND_UP> encoder);

        /**
         * using decoder and encoder to combined for duplex.
         *
         * <ul>
         *  <li>RCV_UP is Message</li>
         *  <li>RCV_DOWN is Message</li>
         *  <li>SND_UP is Message</li>
         *  <li>SND_DOWN is Message</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeMessageToMessageHandler<RCV_UP, RCV_DOWN> decoder, PipeMessageToMessageHandler<SND_UP, SND_DOWN> encoder);

        /**
         * using decoder and encoder to combined for duplex.
         *
         * <ul>
         *  <li>RCV_UP is Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf}</li>
         *  <li>SND_UP is {@link ByteBuf}</li>
         *  <li>SND_DOWN is Message</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeMessageToBytesHandler<RCV_UP> decoder, PipeMessageToBytesHandler<SND_DOWN> encoder);

        /** build {@link PipeLayerStack} */
        PipeLayerStack build();
    }
}