/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import net.hasor.neta.channel.SoChannel;
/**
 * Per-channel TLS state exposed to pipeline handlers.
 * <p>An instance is created by {@link SslDuplex#onInit(String, int, int, net.hasor.neta.channel.ProtoContext)}
 * and stored in the active {@link net.hasor.neta.channel.ProtoContext}. Upper handlers can inspect it
 * to determine whether the TLS handshake has finished, which ALPN protocol was negotiated, and what SNI
 * host was requested.
 * <p><b>Typical usage:</b>
 * <pre>
 *   SslContext ssl = protoContext.context(SslContext.class);
 *   if (ssl != null &amp;&amp; ssl.isReady()) {
 *       String alpn = ssl.getApplicationProtocol();
 *   }
 * </pre>
 * <ul>
 *   <li>{@link #isReady()} reports whether TLS is currently enabled and the handshake reached
 *       {@link SslHandshakeStatus#Finish}.</li>
 *   <li>{@link #getApplicationProtocol()} exposes the negotiated ALPN result or configured fallback.</li>
 *   <li>{@link #getSniHostName()} exposes requested SNI information when available.</li>
 *   <li>{@link #closeSSL()} and {@link #openSSL()} are local control switches on the current SSL wrapper.
 *       They are not a general-purpose renegotiation or full STARTTLS orchestration API by themselves.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 * @see SslDuplex
 * @see SslConfig
 */
public interface SslContext {

    /** return ssl cert config. */
    SslCertConfig getConfig();

    SoChannel<?> getChannel();

    /** SSL server side */
    boolean isServer();

    /** SSL Client side */
    boolean isClient();

    /** return SSL status Active */
    boolean isReady();

    /**
     * Returns the name of the negotiated application-level protocol.
     * @return the application-level protocol name or {@code null} if the negotiation failed or the client does not have ALPN/NPN extension
     */
    String getApplicationProtocol();

    /** Returns the host name of the peer of this session. The host name is not authenticated. */
    String getPeerHost();

    /** Returns the host name of the peer of this session. The host port is not authenticated. */
    int getPeerPort();

    /** Returns the SNI (Server Name Indication) host name. */
    String getSniHostName();

    /** switch to no encryption, there is keep connect, close SSL. */
    void closeSSL();

    /** switch to encryption, there is keep connect, close SSL. */
    void openSSL();

    //    /** switch to no encryption, there is keep connect, close SSL. */
    //    void renegotiate();
}
