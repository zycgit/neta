/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import java.io.IOException;
import java.util.Objects;
import javax.net.ssl.SSLHandshakeException;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * TLS record-layer duplexer for byte-oriented Neta pipelines.
 * <p>This handler is intended to sit close to the transport edge and convert between raw TLS
 * records and plaintext application bytes. During initialization it creates an
 * {@link SslContext} for the current channel and stores it in the pipeline context so later
 * handlers can inspect handshake completion, negotiated ALPN, peer host information, and SNI.
 * <p><b>Flow:</b>
 * <pre>
 *   network ciphertext
 *       -> SslDuplex (RCV)
 *       -> SSLEngine.unwrap(...)
 *       -> plaintext ByteBuf for upper handlers
 *   application plaintext
 *       -> SslDuplex (SND)
 *       -> SSLEngine.wrap(...)
 *       -> ciphertext ByteBuf for the transport
 * </pre>
 * <ul>
 *   <li><b>Client bootstrap:</b> on the client side {@link #onActive(ProtoContext)} sends an empty
 *       buffer to force the first SND pass and start the handshake immediately.</li>
 *   <li><b>Server bootstrap:</b> on the server side the handshake starts when the first inbound TLS
 *       bytes reach the duplexer.</li>
 *   <li><b>Events:</b> successful handshake completion fires {@link SslHandshakeEvent}; receiving a
 *       peer {@code close_notify} later fires {@link SslCloseNotifyEvent}. Handshake failure does not
 *       emit a dedicated success/failure event here; the channel is closed instead.</li>
 *   <li><b>Graceful close:</b> when the channel-layer {@link SoCloseEvent} travels through the SND
 *       path, this duplexer asks the current {@link SslContext} to emit TLS {@code close_notify}.</li>
 * </ul>
 * <p><b>Pipeline placement:</b>
 * <pre>
 *   ctx.addFirst("ssl", new SslDuplex(sslConfig));
 *   ctx.addLast("http", httpDuplexer);
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @see SslConfig
 * @see SslContext
 * @see SslCertConfig
 */
public class SslDuplex implements ProtoDuplex<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    private static final Logger logger = Logger.getLogger(SslDuplex.class);
    private final SslConfig     config;

    /** Creates an SSL duplexer with the given SSL configuration. */
    public SslDuplex(SslConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        SoChannel<?> channel = context.getChannel();

        if (this.config.getProvider() == SslProvider.JSSE) {
            JdkSslContext ctx = new JdkSslContext(channel, name, context, this.config, channel.isClient());
            context.context(SslContext.class, ctx);
        } else {
            throw new UnsupportedOperationException(this.config.getProvider() + " Unsupported.");
        }
    }

    @Override
    public void onActive(ProtoContext context) throws Exception {
        if (context.getChannel().isClient()) {
            context.sendData(ByteBuf.EMPTY);// make sure to trigger the handshake
        }
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        // when safe-close to send close_notify alert
        if (!isRcv && event.getData() instanceof SoCloseEvent) {
            SslContextBasic ref = (SslContextBasic) context.context(SslContext.class);
            if (ref != null) {
                ref.signalCloseNotify();
            }
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        try {
            if (isRcv) {
                return ((SslContextBasic) context.context(SslContext.class)).handRcv(rcvUp, rcvDown, sndUp, sndDown);
            } else {
                return ((SslContextBasic) context.context(SslContext.class)).handSnd(rcvUp, rcvDown, sndUp, sndDown);
            }
        } catch (SSLHandshakeException e) {
            long channelId = context.getChannel().getChannelId();
            logger.warn("ssl(" + channelId + ") handshake failed: " + e.getMessage());
            context.getChannel().close();
            return ProtoStatus.Stop;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {

    }
}
