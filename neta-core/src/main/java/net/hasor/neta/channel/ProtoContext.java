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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Each {@link NetChannel} has a {@link ProtoContext}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface ProtoContext {
    /** global config */
    NetConfig getConfig();

    /** the channel */
    SoChannel<?> getChannel();

    /** the SoContext */
    SoContext getSoContext();

    /**
     * Returns the name of the current protocol stack name.
     * <p><b>Note:</b> This method will only return a valid result when called within the processing flow
     * of {@link ProtoDuplexer} or {@link ProtoHandler}.</p>
     * @return the name of the current protocol stack
     */
    String getStackName();

    /** Get the attachment for {@link ProtoContext} */
    <T> T context(Class<T> attachment);

    /** bind attachment to {@link ProtoContext} */
    <T> T context(Class<T> attachmentType, T attachment);

    /** Get flash for current ProtoStack event. */
    <T> T flash(String key);

    /**
     * set flash to current {@link ProtoContext}, the flash event.
     * <p>flash has a very short lifetime and will be automatically cleaned up, After the event propagation is complete</p>
     * if you need long-term storage Use {@link #context(Class, Object)}
     */
    <T> T flash(String key, T flash);

    /** write data to protocol stack, the event propagates backward from the current protocol layer */
    Future<?> sendData(Object writeData);

    <T> void fireUserEvent(Class<T> eventType, T event);

    /** Refresh the protocol stack, the event propagates backward from the current protocol layer */
    Future<?> flush();

    /** return ByteBufAllocator from SoContext. */
    ByteBufAllocator byteBufAllocator();

    /** Returns the pipeline mode is receive */
    boolean isRcv();

    /** Returns the pipeline mode is sent */
    boolean isSnd();

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
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

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
    void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer);

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
    void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

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
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * using decoder and encoder to combined for duplex.
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

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
    void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer);

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
    void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>RCV_UP equal to RCV_DOWN</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstEncoder(ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addFirstEncoder(String name, ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>RCV_UP equal to RCV_DOWN</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastEncoder(ProtoHandler<?, ?> encoder);

    /**
     * using encoder, the decoder is transparent
     * <ul>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param name stack name
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the specified handler is {@code null}
     */
    void addLastEncoder(String name, ProtoHandler<?, ?> encoder);

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
    void addFirstDecoder(ProtoHandler<?, ?> decoder);

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
    void addFirstDecoder(String name, ProtoHandler<?, ?> decoder);

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
    void addLastDecoder(ProtoHandler<?, ?> decoder);

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
    void addLastDecoder(String name, ProtoHandler<?, ?> decoder);
}