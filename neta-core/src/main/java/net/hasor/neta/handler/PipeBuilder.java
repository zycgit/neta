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
import net.hasor.neta.channel.PipeInitializer;
import net.hasor.neta.channel.Pipeline;

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
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(PipeDuplex<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> duplexer) {
        return this.nextDuplex(duplexer.getClass().getSimpleName(), this.pipeConfig(), duplexer);
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
     * @param name duplexer name
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, PipeDuplex<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> duplexer) {
        return this.nextDuplex(name, this.pipeConfig(), duplexer);
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
     * @param name duplexer name
     * @param pipeConfig duplexer config
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeDuplex<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> duplexer);

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
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        String decName = decoder.getClass().getSimpleName();
        String encName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;
        encName = StringUtils.isBlank(encName) ? "Unknown" : encName;

        String name = String.format("%s/%s", decName, encName);
        return this.nextHandler(name, this.pipeConfig(), decoder, encoder);
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
    default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(String name, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        return this.nextHandler(name, this.pipeConfig(), decoder, encoder);
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
    <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(String name, PipeConfig pipeConfig, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder);

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
         * @param duplexer target duplexer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
            return this.nextDuplex(duplexer.getClass().getSimpleName(), this.pipeConfig(), duplexer);
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
         * @param name duplexer name
         * @param duplexer target duplexer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
            return this.nextDuplex(name, this.pipeConfig(), duplexer);
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
         * @param name duplexer name
         * @param pipeConfig duplexer config
         * @param duplexer target duplexer
         * @throws NullPointerException if the specified handler is {@code null}
         */
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer);

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
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
            String decName = decoder.getClass().getSimpleName();
            String encName = encoder.getClass().getSimpleName();
            decName = StringUtils.isBlank(decName) ? "Unknown" : decName;
            encName = StringUtils.isBlank(encName) ? "Unknown" : encName;

            String name = String.format("%s/%s", decName, encName);
            return this.nextHandler(name, this.pipeConfig(), decoder, encoder);
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
        default <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(String name, PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
            return this.nextHandler(name, this.pipeConfig(), decoder, encoder);
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
        <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(String name, PipeConfig pipeConfig, PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder);

        /**
         * using decoder, the encoder is transparent
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN equal to SND_UP</li>
         * </ul>
         *
         * @param decoder RCV_UP to RCV_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN> PipelineBuilder<RCV_DOWN, SND_DOWN> nextDecoder(PipeHandler<RCV_UP, RCV_DOWN> decoder) {
            String decName = decoder.getClass().getSimpleName();
            decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

            String name = String.format("%s/--", decName);
            return this.nextDecoder(name, this.pipeConfig(), decoder);
        }

        /**
         * using decoder, the encoder is transparent
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN equal to SND_UP</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param decoder RCV_UP to RCV_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN> PipelineBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, PipeHandler<RCV_UP, RCV_DOWN> decoder) {
            return this.nextDecoder(name, this.pipeConfig(), decoder);
        }

        /**
         * using decoder, the encoder is transparent
         * <ul>
         *  <li>RCV_UP is {@link ByteBuf} or Message</li>
         *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN equal to SND_UP</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param pipeConfig pipeLayer config
         * @param decoder RCV_UP to RCV_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <RCV_DOWN> PipelineBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, PipeConfig pipeConfig, PipeHandler<RCV_UP, RCV_DOWN> decoder) {
            return this.nextHandler(name, pipeConfig, decoder, new CourierHandler<>());
        }

        /**
         * using encoder, the decoder is transparent
         * <ul>
         *  <li>RCV_UP equal to RCV_DOWN</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <SND_UP> PipelineBuilder<RCV_UP, SND_UP> nextEncoder(PipeHandler<SND_UP, SND_DOWN> encoder) {
            String decName = encoder.getClass().getSimpleName();
            decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

            String name = String.format("--/%s", decName);
            return this.nextEncoder(name, this.pipeConfig(), encoder);
        }

        /**
         * using encoder, the decoder is transparent
         * <ul>
         *  <li>RCV_UP equal to RCV_DOWN</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <SND_UP> PipelineBuilder<RCV_UP, SND_UP> nextEncoder(String name, PipeHandler<SND_UP, SND_DOWN> encoder) {
            return this.nextEncoder(name, this.pipeConfig(), encoder);
        }

        /**
         * using encoder, the decoder is transparent
         * <ul>
         *  <li>RCV_UP equal to RCV_DOWN</li>
         *  <li>SND_UP is {@link ByteBuf} or Message</li>
         *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
         * </ul>
         *
         * @param name pipeLayer name
         * @param pipeConfig pipeLayer config
         * @param encoder SND_UP to SND_DOWN
         * @throws NullPointerException if the specified handler is {@code null}
         */
        default <SND_UP> PipelineBuilder<RCV_UP, SND_UP> nextEncoder(String name, PipeConfig pipeConfig, PipeHandler<SND_UP, SND_DOWN> encoder) {
            return this.nextHandler(name, pipeConfig, new CourierHandler<>(), encoder);
        }

        /** build {@link Pipeline} */
        default PipeInitializer build() {
            return this.build(this.pipeConfig());
        }

        /** build {@link Pipeline} */
        PipeInitializer build(PipeConfig rootConfig);
    }
}