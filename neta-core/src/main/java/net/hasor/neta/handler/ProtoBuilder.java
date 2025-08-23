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
import net.hasor.neta.channel.ProtoStack;

import java.util.Objects;

/**
 * Application stack builder
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public interface ProtoBuilder<RCV_UP, SND_DOWN> {

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
        return this.nextDuplex(duplexer.getClass().getSimpleName(), ProtoConfig.DEFAULT, duplexer);
    }

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name duplexer name
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
        return this.nextDuplex(name, ProtoConfig.DEFAULT, duplexer);
    }

    /**
     * this is a Duplexer, The data flow direction is identified by the isRcv parameter.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name duplexer name
     * @param protoConf duplexer config
     * @param duplexer target duplexer
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        String decName = decoder.getClass().getSimpleName();
        String encName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;
        encName = StringUtils.isBlank(encName) ? "Unknown" : encName;

        String name = String.format("%s/%s", decName, encName);
        return this.nextDuplex(name, ProtoConfig.DEFAULT, decoder, encoder);
    }

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        return this.nextDuplex(name, ProtoConfig.DEFAULT, decoder, encoder);
    }

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param protoConf stack config
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder);

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN equal to SND_UP</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        String decName = decoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

        String name = String.format("%s/--", decName);
        return this.nextDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        return this.nextDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /**
     * using decoder, the encoder is transparent
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     *
     * @param name stack name
     * @param protoConf stack config
     * @param decoder RCV_UP to RCV_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, ProtoConfig protoConf, ProtoHandler<RCV_UP, RCV_DOWN> decoder);

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
    default <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(ProtoHandler<SND_UP, SND_DOWN> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        String decName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

        String name = String.format("--/%s", decName);
        return this.nextEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     *
     * @param name stack name
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    default <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(String name, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        return this.nextEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param protoConf stack config
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(String name, ProtoConfig protoConf, ProtoHandler<SND_UP, SND_DOWN> encoder);

    /** build {@link ProtoStack} */
    default <T> ProtoStack<T> build() {
        return this.build(null);
    }

    /** build {@link ProtoStack} */
    <T> ProtoStack<T> build(EventBus eventBus);
}
