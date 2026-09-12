/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl.tcp;

import static net.hasor.neta.codec.AbstractSoTest.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.ssl.*;

/**
 * Verifies that a Neta TLS channel emits a proper TLS {@code close_notify} alert
 * before the TCP connection is torn down when {@link NetChannel#close()} is called.
 * <h3>Detection strategy</h3>
 * Both sides use Neta TLS.  When the client sends close_notify, the server's
 * {@link SslContextBasic} processes it (via {@link SslHandle#handlerRcv}), which
 * transitions the SSL layer to {@link SslHandshakeStatus#Closed} before the TCP FIN
 * arrives.  In the server-side {@code onClose} callback we check
 * {@link SslContext#isReady()}:
 * <ul>
 *   <li>{@code false} — close_notify was received before TCP teardown ✓</li>
 *   <li>{@code true}  — TCP closed without prior close_notify (force-close) ✓</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-05
 */
public class TcpSslCloseNotifyTest extends AbstractSslTest {

    /**
     * A lightweight handler that records, at {@code onClose} time, whether the
     * SSL layer had already been shut down (i.e. close_notify received from peer).
     */
    private static ProtoInitializer buildServerProto(SslConfig sslConf, AtomicBoolean sslActiveAtClose, CountDownLatch closeLatch) {
        ProtoHandler<String, String> serverHandler = new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
                // Look up the SslContext from the protocol context chain
                SslContext raw = context.context(SslContext.class);
                if (raw != null) {
                    // false == SSL was cleanly shut-down by close_notify BEFORE this TCP onClose
                    sslActiveAtClose.set(raw.isReady());
                }
                closeLatch.countDown();
            }
        };
        return SoSslUtils.tcpSslSocketProtoStack(sslConf, serverHandler);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Safe-close ({@code channel.close()}) on the Neta TLS client MUST send a
     * TLS {@code close_notify} alert to the server before the TCP connection closes.
     * <p>
     * Expected: the server's SSL layer is already inactive ({@code isReady()==false})
     * when {@code onClose} fires — proving close_notify arrived before the TCP FIN.
     */
    @Test
    public void neta_client_safeClose_sends_closeNotify_tls12() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        AtomicBoolean sslActiveAtClose = new AtomicBoolean(true); // default: assume still active
        CountDownLatch serverCloseLatch = new CountDownLatch(1);

        NetManager neta = new NetManager(globalConf());

        // Server: Neta TLS — handler checks ssl.isReady() at onClose time
        NetListen listen = neta.bind(address, buildServerProto(sslConf, sslActiveAtClose, serverCloseLatch), tcpConf);

        // Client: Neta TLS
        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }
        });
        NetChannel client = neta.connectAsync(address, clientProto, tcpConf).get();
        listen.waitAnyAccept();

        // Wait for TLS handshake on both sides
        SslContext clientSsl = SslUtils.getSslContextFromRoot(client);
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        SslContext serverSsl = SslUtils.getSslContextFromRoot(serverSide);

        long deadline = System.currentTimeMillis() + 5000;
        while ((!clientSsl.isReady() || !serverSsl.isReady()) && System.currentTimeMillis() < deadline) {
            ThreadUtils.sleep(50);
        }
        assert clientSsl.isReady() && serverSsl.isReady() : "TLS handshake did not complete";

        // Safe-close: fires SoCloseEvent → SslDuplex → close_notify → then TCP FIN
        client.close();

        // Wait for server onClose
        boolean closed = serverCloseLatch.await(5, TimeUnit.SECONDS);
        assert closed : "server onClose never fired";
        neta.shutdown();

        // KEY assertion: server SSL was already inactive when onClose fired
        // → close_notify arrived BEFORE the TCP FIN
        assert !sslActiveAtClose.get() : "TLS close_notify must arrive at server before TCP close (safe-close)";
    }

    /**
     * Force-close ({@code channel.closeNow()}) on the Neta TLS client MUST NOT
     * send a TLS {@code close_notify} — the TCP connection is torn down immediately.
     * <p>
     * Expected: the server's SSL layer is still active ({@code isReady()==true})
     * when {@code onClose} fires — proving NO close_notify arrived before the TCP FIN.
     */
    @Test
    public void neta_client_forceClose_does_not_send_closeNotify_tls12() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        AtomicBoolean sslActiveAtClose = new AtomicBoolean(false); // default: assume inactive
        CountDownLatch serverCloseLatch = new CountDownLatch(1);

        NetManager neta = new NetManager(globalConf());

        NetListen listen = neta.bind(address, buildServerProto(sslConf, sslActiveAtClose, serverCloseLatch), tcpConf);

        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }
        });
        NetChannel client = neta.connectAsync(address, clientProto, tcpConf).get();
        listen.waitAnyAccept();

        SslContext clientSsl = SslUtils.getSslContextFromRoot(client);
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        SslContext serverSsl = SslUtils.getSslContextFromRoot(serverSide);

        long deadline = System.currentTimeMillis() + 5000;
        while ((!clientSsl.isReady() || !serverSsl.isReady()) && System.currentTimeMillis() < deadline) {
            ThreadUtils.sleep(50);
        }
        assert clientSsl.isReady() && serverSsl.isReady() : "TLS handshake did not complete";

        // Force-close: skips SoCloseEvent, no close_notify, immediate TCP FIN
        client.closeNow();

        boolean closed = serverCloseLatch.await(5, TimeUnit.SECONDS);
        assert closed : "server onClose never fired";
        neta.shutdown();

        // KEY assertion: server SSL was still active when onClose fired
        // → no close_notify was received before the TCP FIN (expected for force-close)
        assert sslActiveAtClose.get() : "force-close must NOT send TLS close_notify (SSL should still be active at server onClose)";
    }

    /**
     * TCP half-close: client sends a TLS {@code close_notify} alert without
     * closing the TCP connection.  The server must receive a
     * {@link SslCloseNotifyEvent} on its RCV pipeline, and its SSL layer must
     * transition to "closed" ({@code isReady()==false}) while the TCP channel itself
     * remains open.
     */
    @Test
    public void neta_ssl_closeNotify_halfClose_fires_event_tls12() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        AtomicReference<SslCloseNotifyEvent> capturedEvent = new AtomicReference<>();
        CountDownLatch closeNotifyLatch = new CountDownLatch(1);

        NetManager neta = new NetManager(globalConf());

        // Server: captures SslCloseNotifyEvent when it arrives on the RCV pipeline
        ProtoInitializer serverProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }

            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getEventType() == SslCloseNotifyEvent.class) {
                    capturedEvent.set((SslCloseNotifyEvent) event.getData());
                    closeNotifyLatch.countDown();
                }
                return true;
            }
        });
        NetListen listen = neta.bind(address, serverProto, tcpConf);

        // Client: standard TLS, no special close handling
        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }
        });
        NetChannel client = neta.connectAsync(address, clientProto, tcpConf).get();
        listen.waitAnyAccept();

        // Wait for TLS handshake to complete on both sides
        SslContext clientSsl = SslUtils.getSslContextFromRoot(client);
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        SslContext serverSsl = SslUtils.getSslContextFromRoot(serverSide);

        long deadline = System.currentTimeMillis() + 5000;
        while ((!clientSsl.isReady() || !serverSsl.isReady()) && System.currentTimeMillis() < deadline) {
            ThreadUtils.sleep(50);
        }
        assert clientSsl.isReady() && serverSsl.isReady() : "TLS handshake did not complete";

        // Client sends close_notify WITHOUT closing TCP
        clientSsl.closeSSL(); // sends close_notify then disables SSL, TCP stays open

        // Wait for server to receive SslCloseNotifyEvent
        boolean eventReceived = closeNotifyLatch.await(5, TimeUnit.SECONDS);
        assert eventReceived : "server did not receive SslCloseNotifyEvent within timeout";

        // KEY assertions:
        // 1. The event payload carries the server-side SslContext
        assert capturedEvent.get() != null : "SslCloseNotifyEvent must be captured";
        assert capturedEvent.get().getContext() == serverSsl : "event context must be the server SslContext";

        // 2. SSL layer is now closed on the server side
        assert !serverSsl.isReady() : "server isReady() must be false after receiving close_notify";
        assert !serverSsl.isReady() : "server isReady() must be false after receiving close_notify";

        // 3. TCP connection is still alive — no channel.close() was called
        assert !serverSide.isClose() : "TCP channel must remain open after SSL half-close";
        assert !client.isClose() : "client TCP channel must remain open after SSL half-close";

        neta.shutdown();
    }

    /**
     * {@link SslContextBasic#closeSSL()} MUST transmit a TLS {@code close_notify}
     * alert to the peer, exactly like a safe-close does, but without closing the
     * TCP connection.
     * <p>
     * After {@code closeSSL()} the client SSL layer is disabled
     * ({@code isReady()==false}) and the server must receive a
     * {@link SslCloseNotifyEvent} proving the close_notify bytes reached the wire.
     */
    @Test
    public void neta_closeSSL_sends_closeNotify_tls12() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        AtomicReference<SslCloseNotifyEvent> capturedEvent = new AtomicReference<>();
        CountDownLatch closeNotifyLatch = new CountDownLatch(1);

        NetManager neta = new NetManager(globalConf());

        // Server: captures SslCloseNotifyEvent when it arrives on the RCV pipeline
        ProtoInitializer serverProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }

            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getEventType() == SslCloseNotifyEvent.class) {
                    capturedEvent.set((SslCloseNotifyEvent) event.getData());
                    closeNotifyLatch.countDown();
                }
                return true;
            }
        });
        NetListen listen = neta.bind(address, serverProto, tcpConf);

        // Client: standard TLS
        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown) {
                return ProtoStatus.Next;
            }
        });
        NetChannel client = neta.connectAsync(address, clientProto, tcpConf).get();
        listen.waitAnyAccept();

        // Wait for TLS handshake to complete on both sides
        SslContext clientSsl = SslUtils.getSslContextFromRoot(client);
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        SslContext serverSsl = SslUtils.getSslContextFromRoot(serverSide);

        long deadline = System.currentTimeMillis() + 5000;
        while ((!clientSsl.isReady() || !serverSsl.isReady()) && System.currentTimeMillis() < deadline) {
            ThreadUtils.sleep(50);
        }
        assert clientSsl.isReady() && serverSsl.isReady() : "TLS handshake did not complete";

        // closeSSL(): sends close_notify then disables SSL (no TCP close)
        clientSsl.closeSSL();

        // Wait for server to receive SslCloseNotifyEvent
        boolean eventReceived = closeNotifyLatch.await(5, TimeUnit.SECONDS);
        assert eventReceived : "server did not receive SslCloseNotifyEvent — closeSSL() did not send close_notify";

        // KEY assertions:
        // 1. Server received the event with the correct SslContext
        assert capturedEvent.get() != null : "SslCloseNotifyEvent must be captured";
        assert capturedEvent.get().getContext() == serverSsl : "event context must be the server SslContext";

        // 2. Client SSL is now disabled
        assert !clientSsl.isReady() : "client isReady() must be false after closeSSL()";

        // 3. Server SSL layer is closed (received close_notify)
        assert !serverSsl.isReady() : "server isReady() must be false after receiving close_notify";

        // 4. TCP connection is still alive — closeSSL() must not close TCP
        assert !serverSide.isClose() : "TCP channel must remain open after closeSSL()";
        assert !client.isClose() : "client TCP channel must remain open after closeSSL()";

        neta.shutdown();
    }
}
