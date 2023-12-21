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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.Pipeline;
import net.hasor.neta.channel.PipelineFactory;

/**
 * Application stack builder
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
public interface PipeBuilder {
    /**
     * empty PipelineBuilder is mostly used to develop the protocol stack via Embedded.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is Undefined</li>
     *  <li>SND_UP is Undefined</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     *
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_UP, SND_DOWN> PipelineBuilder<RCV_UP, SND_DOWN> pipeConfig(PipeConfig pipeConfig);

    /** Get the default PipeConfig */
    PipeConfig pipeConfig();

    /**
     * empty PipelineBuilder is mostly used to develop the protocol stack via Embedded.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is Undefined</li>
     *  <li>SND_UP is Undefined</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     *
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_UP, SND_DOWN> PipelineBuilder<RCV_UP, SND_DOWN> nextTo();

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param pipeLayer target pipeLayer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(PipeLayer<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> pipeLayer) {
        return this.nextTo(pipeLayer.getClass().getSimpleName(), this.pipeConfig(), pipeLayer);
    }

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param name pipeLayer name
     * @param pipeLayer target pipeLayer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeLayer<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> pipeLayer) {
        return this.nextTo(name, this.pipeConfig(), pipeLayer);
    }

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     *
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param name pipeLayer name
     * @param pipeConfig pipeLayer config
     * @param pipeLayer target pipeLayer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeLayer<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> pipeLayer);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        String decName = decoder.getClass().getSimpleName();
        String encName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;
        encName = StringUtils.isBlank(encName) ? "Unknown" : encName;

        String name = String.format("%s/%s", decName, encName);
        return this.nextTo(name, this.pipeConfig(), decoder, encoder);
    }

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param name pipeLayer name
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        return this.nextTo(name, this.pipeConfig(), decoder, encoder);
    }

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf}</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf}</li>
     * </ul>
     *
     * @param name pipeLayer name
     * @param pipeConfig pipeLayer config
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder);

    interface PipelineBuilder<RCV_UP, SND_DOWN> {
        /**
         * empty PipelineBuilder is mostly used to develop the protocol stack via Embedded.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is Undefined</li>
         *  <li>SND_UP is Undefined</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> pipeConfig(PipeConfig pipeConfig);

        /** Get the default PipeConfig */
        PipeConfig pipeConfig();

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param pipeLayer target pipeLayer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
            return this.nextTo(pipeLayer.getClass().getSimpleName(), this.pipeConfig(), pipeLayer);
        }

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param pipeLayer target pipeLayer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
            return this.nextTo(name, this.pipeConfig(), pipeLayer);
        }

        /**
         * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
         *
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param pipeConfig pipeLayer config
         * @param pipeLayer target pipeLayer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer);

        /**
         * using decoder and encoder to combined for duplex.
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
            String decName = decoder.getClass().getSimpleName();
            String encName = encoder.getClass().getSimpleName();
            decName = StringUtils.isBlank(decName) ? "Unknown" : decName;
            encName = StringUtils.isBlank(encName) ? "Unknown" : encName;

            String name = String.format("%s/%s", decName, encName);
            return this.nextTo(name, this.pipeConfig(), decoder, encoder);
        }

        /**
         * using decoder and encoder to combined for duplex.
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
            return this.nextTo(name, this.pipeConfig(), decoder, encoder);
        }

        /**
         * using decoder and encoder to combined for duplex.
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param pipeConfig pipeLayer config
         * @param decoder RCV_UP to RCV_DOWN
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder);

        /**
         * It is used to receive network data after being processed by the protocol stack.
         */
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> bindReceive(PipeListener<RCV_DOWN> listener);

        /** build {@link Pipeline} */
        default PipelineFactory build() {
            return this.build(this.pipeConfig());
        }

        /** build {@link Pipeline} */
        PipelineFactory build(PipeConfig rootConfig);
    }
}