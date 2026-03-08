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
package net.hasor.neta.codec.ssl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtListen;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * Tests for SSL protocol auto-negotiation using routing pipeline (sub-pipeline).
 * <p>
 * Demonstrates:
 * <ul>
 *   <li>Port unification: TLS vs plaintext auto-detection via first-byte routing</li>
 *   <li>ALPN protocol negotiation within SSL branch</li>
 *   <li>SSL + routing pipeline integration for protocol multiplexing</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslRoutingTest extends AbstractSslTest {

    private static SslConfig sslConfig() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }

    // =================================================================
    //  Helper: Port unification pipeline (TLS vs plaintext detection)
    // =================================================================
    private static ProtoInitializer createPortUnificationStack(SslConfig sslConf) {
        return ProtoHelper.standard()//
                .<ByteBuf, ByteBuf>nextRouteAsStatic("router", (context, rcvUp, rcvDown) -> {
                    // rcvUp is null during onActive (connection init) — no data yet, defer routing
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    ByteBuf data = (ByteBuf) rcvUp.peekMessage();
                    if (data != null && data.readableBytes() > 0) {
                        byte firstByte = data.getByte(data.readerIndex());
                        return (firstByte == 0x16) ? "tls" : "plain";
                    }
                    return null; // not enough data
                }, r -> {
                    // TLS branch: SSL decryption → string codec
                    r.branch("tls", (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch
                            .nextDuplex("SSL", new SslDuplexer(sslConf))
                            .nextDuplex("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1));
                    // Plaintext branch: direct string codec
                    r.branch("plain", (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch
                            .nextDuplex("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1));
                }).build();
    }

    // =================================================================
    //  Helper: Plain text pipeline (no SSL)
    // =================================================================
    private static ProtoInitializer createPlainTextStack() {
        return ProtoHelper.standard().nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1).build();
    }

    // =================================================================
    //  Helper: ALPN-based routing after SSL
    //
    //  [SSL] → [ALPN Router] ─── "http/2"      ─── [H2TagHandler] → [StringCodec]
    //                        └── "http/1.1" ── [Http11TagHandler] → [StringCodec]
    // =================================================================
    private static ProtoInitializer createAlpnRoutingStack(SslConfig sslConf, List<String> h2Events, List<String> http11Events) {
        return ctx -> {
            // SSL layer
            ctx.addLast("SSL", new SslDuplexer(sslConf));

            // ALPN-based router: after SSL decryption, route by negotiated protocol
            ProtoRoutingDuplexer<ByteBuf, ByteBuf> alpnRouter = new ProtoRoutingDuplexer<>((context, rcvUp, rcvDown) -> {
                SslContext sslContext = context.context(SslContext.class);
                if (sslContext != null && sslContext.isReady()) {
                    String proto = sslContext.getApplicationProtocol();
                    if ("http/2".equals(proto)) {
                        return "http/2";
                    }
                    return "http/1.1"; // ALPN negotiated non-h2 protocol
                }
                return null; // SSL not ready yet, defer routing
            });

            alpnRouter.addBranch("http/2", branch -> {
                branch.addLast("tag", createTagHandler("http/2", h2Events));
                branch.addLast("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1);
            });

            alpnRouter.addBranch("http/1.1", branch -> {
                branch.addLast("tag", createTagHandler("http/1.1", http11Events));
                branch.addLast("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1);
            });

            ctx.addLast("alpnRouter", alpnRouter);
        };
    }

    // =================================================================
    //  Helper: Full combined stack (port unification + SSL + ALPN)
    //
    //  [FirstByte Router] ─── "tls"  ─── [SSL] → [ALPN Router] ─── "http/2"      ── [Tag] → [String]
    //                     │                                     └── "http/1.1" ── [Tag] → [String]
    //                     └── "plain" ── [StringCodec]
    // =================================================================
    private static ProtoInitializer createFullStack(SslConfig sslConf, List<String> h2Events, List<String> http11Events) {
        return ctx -> {
            ProtoRoutingDuplexer<ByteBuf, ByteBuf> outerRouter = new ProtoRoutingDuplexer<>((context, rcvUp, rcvDown) -> {
                // rcvUp is null during onActive (connection init) — no data yet, defer routing
                if (rcvUp.queueSize() == 0) {
                    return null;
                }
                ByteBuf data = rcvUp.peekMessage();
                if (data != null && data.readableBytes() > 0) {
                    byte firstByte = data.getByte(data.readerIndex());
                    return (firstByte == 0x16) ? "tls" : "plain";
                }
                return null;
            });

            // TLS branch with nested ALPN routing
            outerRouter.addBranch("tls", branch -> {
                branch.addLast("SSL", new SslDuplexer(sslConf));

                // Nested ALPN router within the TLS branch
                ProtoRoutingDuplexer<ByteBuf, ByteBuf> alpnRouter = new ProtoRoutingDuplexer<>((context, rcvUp, rcvDown) -> {
                    SslContext sslContext = context.context(SslContext.class);
                    if (sslContext != null && sslContext.isReady()) {
                        String proto = sslContext.getApplicationProtocol();
                        if ("http/2".equals(proto)) {
                            return "http/2";
                        }
                        return "http/1.1"; // ALPN negotiated non-h2 protocol
                    }
                    return null; // SSL not ready yet, defer routing
                });

                alpnRouter.addBranch("http/2", b -> {
                    b.addLast("tag", createTagHandler("http/2", h2Events));
                    b.addLast("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1);
                });

                alpnRouter.addBranch("http/1.1", b -> {
                    b.addLast("tag", createTagHandler("http/1.1", http11Events));
                    b.addLast("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1);
                });

                branch.addLast("alpnRouter", alpnRouter);
            });

            // Plaintext branch
            outerRouter.addBranch("plain", branch -> {
                branch.addLast("string", (ProtoHandler<ByteBuf, String>) AbstractSslTest::doDecoder1, (ProtoHandler<String, ByteBuf>) AbstractSslTest::doEncoder1);
            });

            ctx.addLast("router", outerRouter);
        };
    }

    // =================================================================
    //  Helper: Tag handler — prefixes messages with protocol tag
    //  and records events for test assertions
    //
    //  RCV: ByteBuf → ByteBuf (prepend "[proto]" to content)
    //  SND: pass through
    // =================================================================
    @SuppressWarnings("unchecked")
    private static ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> createTagHandler(String proto, List<String> events) {
        return new ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf>() {
            @Override
            public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
                events.add("init");
            }

            @Override
            public void onActive(ProtoContext context) {
                events.add("active");
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
                if (isRcv) {
                    events.add("message");
                    ByteBuf data;
                    while ((data = rcvUp.takeMessage()) != null) {
                        // Prepend protocol tag
                        byte[] tag = ("[" + proto + "]").getBytes();
                        byte[] original = new byte[(int) data.readableBytes()];
                        data.readBytes(original);
                        byte[] tagged = new byte[tag.length + original.length];
                        System.arraycopy(tag, 0, tagged, 0, tag.length);
                        System.arraycopy(original, 0, tagged, tag.length, original.length);
                        rcvDown.offerMessage(ByteBuf.wrap(tagged));
                    }
                } else {
                    // SND passthrough
                    ByteBuf data;
                    while ((data = sndUp.takeMessage()) != null) {
                        sndDown.offerMessage(data);
                    }
                }
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                events.add("error");
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
                events.add("close");
            }
        };
    }

    // =====================================================================
    // Test 1: Port unification — SSL vs plaintext auto-detection
    //
    // Server pipeline:
    //   [Router] ─── "tls"  ─── [SslDuplexer] → [StringCodec]
    //            └── "plain" ── [StringCodec]
    //
    // Client sends plaintext → routed to "plain" branch
    // =====================================================================
    @Test
    public void routing_portUnification_plaintext() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = sslConfig();

            // Server: routing pipeline that detects TLS vs plaintext
            ProtoInitializer serverStack = createPortUnificationStack(sslConf);

            // Client: plain text (no SSL)
            ProtoInitializer clientStack = createPlainTextStack();

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.subscribe(PlayLoad::isInbound, d -> clientRcvData.offer(d.getData()));

            // Client sends plaintext message
            client.sendData("Hello Plaintext Server\n");
            ThreadUtils.sleep(500);

            // Server should receive via plain branch
            assert !serverRcvData.isEmpty() : "Server should receive plaintext message";
            assert serverRcvData.poll().equals("Hello Plaintext Server") : "Message content mismatch";

            // Server responds
            server.sendData("Plaintext Response\n");
            ThreadUtils.sleep(500);

            assert !clientRcvData.isEmpty() : "Client should receive response";
            assert clientRcvData.poll().equals("Plaintext Response") : "Response content mismatch";
        });
    }

    // =====================================================================
    // Test 2: Port unification — SSL connection detected and routed
    //
    // Server pipeline:
    //   [Router] ─── "tls"  ─── [SslDuplexer] → [StringCodec]
    //            └── "plain" ── [StringCodec]
    //
    // Client uses SSL → routed to "tls" branch → SSL handshake → data exchange
    // =====================================================================
    @Test
    public void routing_portUnification_ssl() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = sslConfig();

            // Server: routing pipeline that detects TLS vs plaintext
            ProtoInitializer serverStack = createPortUnificationStack(sslConf);

            // Client: SSL-enabled
            ProtoInitializer clientStack = createProtoStack(sslConf);

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.subscribe(PlayLoad::isInbound, d -> clientRcvData.offer(d.getData()));

            // Wait for SSL handshake
            ThreadUtils.sleep(1000);

            // Client sends encrypted message
            client.sendData("Hello SSL Server\n");
            ThreadUtils.sleep(500);

            assert !serverRcvData.isEmpty() : "Server should receive decrypted message via TLS branch";
            assert serverRcvData.poll().equals("Hello SSL Server") : "Decrypted message mismatch";

            // Server responds
            server.sendData("SSL Response\n");
            ThreadUtils.sleep(500);

            assert !clientRcvData.isEmpty() : "Client should receive encrypted response";
            assert clientRcvData.poll().equals("SSL Response") : "Response content mismatch";
        });
    }

    // =====================================================================
    // Test 3: ALPN negotiation — server selects "h2" protocol via ALPN
    //
    // Both client and server configure ALPN with ["h2", "http/1.1"]
    // Server selector returns "h2"
    // After handshake, verify negotiated protocol is "h2"
    // =====================================================================
    @Test
    public void routing_alpn_h2Selected() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            serverConf.setAppProtocolSelector((channel, protocols) -> {
                // Server prefers http/2
                if (protocols.contains("http/2")) {
                    return "http/2";
                }
                return protocols.get(0);
            });

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, createProtoStack(serverConf), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, createProtoStack(clientConf), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            ThreadUtils.sleep(1000);

            SslContext serverSSL = server.findProtoContext(SslContext.class);
            SslContext clientSSL = client.findProtoContext(SslContext.class);

            assert "http/2".equals(serverSSL.getApplicationProtocol()) : "Server ALPN should be http/2, got: " + serverSSL.getApplicationProtocol();
            assert "http/2".equals(clientSSL.getApplicationProtocol()) : "Client ALPN should be http/2, got: " + clientSSL.getApplicationProtocol();
        });
    }

    // =====================================================================
    // Test 4: ALPN negotiation — server selects "http/1.1" (fallback)
    //
    // Client offers ["h2", "http/1.1"], server forces "http/1.1"
    // =====================================================================
    @Test
    public void routing_alpn_http11Fallback() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            serverConf.setAppProtocolSelector((channel, protocols) -> {
                // Server only supports http/1.1
                return "http/1.1";
            });

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, createProtoStack(serverConf), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, createProtoStack(clientConf), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            ThreadUtils.sleep(1000);

            SslContext serverSSL = server.findProtoContext(SslContext.class);
            SslContext clientSSL = client.findProtoContext(SslContext.class);

            assert "http/1.1".equals(serverSSL.getApplicationProtocol()) : "Server ALPN should be http/1.1, got: " + serverSSL.getApplicationProtocol();
            assert "http/1.1".equals(clientSSL.getApplicationProtocol()) : "Client ALPN should be http/1.1, got: " + clientSSL.getApplicationProtocol();
        });
    }

    // =====================================================================
    // Test 5: ALPN with routing pipeline — SSL + ALPN-based sub-pipeline
    //
    // Server pipeline:
    //   [SslDuplexer] → [ALPN Router] ─── "h2"      ─── [H2Handler]
    //                                 └── "http/1.1" ── [Http11Handler]
    //
    // After SSL handshake with ALPN, route to appropriate protocol handler
    // =====================================================================
    @Test
    public void routing_alpn_withSubPipeline() throws Throwable {
        this.autoCloseNeta(neta -> {
            List<String> h2Events = new ArrayList<>();
            List<String> http11Events = new ArrayList<>();

            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            serverConf.setAppProtocolSelector((channel, protocols) -> "http/2");

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });

            // Server: SSL → ALPN-based routing
            ProtoInitializer serverStack = createAlpnRoutingStack(serverConf, h2Events, http11Events);

            // Client: standard SSL
            ProtoInitializer clientStack = createProtoStack(clientConf);

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.subscribe(PlayLoad::isInbound, d -> clientRcvData.offer(d.getData()));

            // Wait for SSL handshake + ALPN negotiation
            ThreadUtils.sleep(1000);

            // ALPN should have selected "h2"
            SslContext serverSSL = server.findProtoContext(SslContext.class);
            assert "http/2".equals(serverSSL.getApplicationProtocol()) : "Server ALPN should be http/2";

            // Send data through the h2-routed branch
            client.sendData("http/2 request\n");
            ThreadUtils.sleep(500);

            // h2 branch handler should have processed the message
            assert !serverRcvData.isEmpty() : "Server should receive data via http/2 branch";
            String received = (String) serverRcvData.poll();
            assert received.equals("[http/2]http/2 request") : "http/2 branch should tag message, got: " + received;

            // h2 events should record processing; http/1.1 should not
            assert h2Events.contains("message") : "http/2 handler should have processed message";
            assert !http11Events.contains("message") : "http/1.1 handler should NOT have processed message";
        });
    }

    // =====================================================================
    // Test 6: ALPN → http/1.1 branch with data exchange
    //
    // Same as test 5 but ALPN selects "http/1.1"
    // =====================================================================
    @Test
    public void routing_alpn_http11Branch() throws Throwable {
        this.autoCloseNeta(neta -> {
            List<String> h2Events = new ArrayList<>();
            List<String> http11Events = new ArrayList<>();

            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            serverConf.setAppProtocolSelector((channel, protocols) -> "http/1.1");

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });

            ProtoInitializer serverStack = createAlpnRoutingStack(serverConf, h2Events, http11Events);
            ProtoInitializer clientStack = createProtoStack(clientConf);

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.subscribe(PlayLoad::isInbound, d -> clientRcvData.offer(d.getData()));

            ThreadUtils.sleep(1000);

            SslContext serverSSL = server.findProtoContext(SslContext.class);
            assert "http/1.1".equals(serverSSL.getApplicationProtocol()) : "Server ALPN should be http/1.1";

            client.sendData("http/1.1 request\n");
            ThreadUtils.sleep(500);

            assert !serverRcvData.isEmpty() : "Server should receive data via http/1.1 branch";
            String received = (String) serverRcvData.poll();
            assert received.equals("[http/1.1]http/1.1 request") : "http/1.1 branch should tag message, got: " + received;

            assert http11Events.contains("message") : "http/1.1 handler should have processed message";
            assert !h2Events.contains("message") : "http/2 handler should NOT have processed message";
        });
    }

    // =====================================================================
    // Test 7: ALPN with single protocol — client only offers "http/2"
    // =====================================================================
    @Test
    public void routing_alpn_singleProtocol() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2" });
            serverConf.setAppProtocolSelector((channel, protocols) -> {
                assert protocols.contains("http/2");
                return "http/2";
            });

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2" });

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, createProtoStack(serverConf), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, createProtoStack(clientConf), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            ThreadUtils.sleep(1000);

            SslContext serverSSL = server.findProtoContext(SslContext.class);
            SslContext clientSSL = client.findProtoContext(SslContext.class);

            assert "http/2".equals(serverSSL.getApplicationProtocol()) : "Server should negotiate http/2";
            assert "http/2".equals(clientSSL.getApplicationProtocol()) : "Client should negotiate http/2";

            // Verify data exchange still works
            Queue<Object> serverRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.sendData("single protocol test\n");
            ThreadUtils.sleep(500);
            assert serverRcvData.poll().equals("single protocol test");
        });
    }

    // =====================================================================
    // Test 8: Port unification + ALPN combined
    //
    // Full stack: first-byte routing → TLS branch → ALPN routing
    //
    // [FirstByte Router] ─── "tls"  ─── [SSL] → [ALPN Router] ─── "http/2"      ─ [http/2 Handler]
    //                    │                                     └── "http/1.1"   ─ [HTTP/1.1 Handler]
    //                    └── "plain" ── [StringCodec]
    //
    // Test: SSL client with ALPN → http/2 branch
    // =====================================================================
    @Test
    public void routing_portUnification_plus_alpn() throws Throwable {
        this.autoCloseNeta(neta -> {
            List<String> h2Events = new ArrayList<>();
            List<String> http11Events = new ArrayList<>();

            SslConfig serverConf = sslConfig();
            serverConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            serverConf.setAppProtocolSelector((channel, protocols) -> "http/2");

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });

            // Server: first-byte routing → SSL → ALPN routing
            ProtoInitializer serverStack = createFullStack(serverConf, h2Events, http11Events);

            // Client: SSL with ALPN
            ProtoInitializer clientStack = createProtoStack(clientConf);

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Queue<Object> serverRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));

            ThreadUtils.sleep(1000);

            SslContext serverSSL = server.findProtoContextByPath(SslContext.class, "router", "tls");
            assert "http/2".equals(serverSSL.getApplicationProtocol()) : "Server should negotiate http/2 via ALPN";

            client.sendData("combined test\n");
            ThreadUtils.sleep(500);

            assert !serverRcvData.isEmpty() : "Server should receive http/2-tagged data";
            String received = (String) serverRcvData.poll();
            assert received.equals("[http/2]combined test") : "Should be tagged by http/2 handler, got: " + received;

            assert h2Events.contains("message") : "http/2 handler should process";
            assert !http11Events.contains("message") : "http/1.1 handler should not process";
        });
    }

    // =====================================================================
    // Test 9: ALPN negotiation via SslEvent user event
    //
    // Verify that SslEvent (handshake completion) is propagated through
    // the routing pipeline to sub-pipeline branches
    // =====================================================================
    @Test
    public void routing_sslEvent_propagation() throws Throwable {
        this.autoCloseNeta(neta -> {
            AtomicReference<SslHandshakeEvent> capturedEvent = new AtomicReference<>();
            CountDownLatch eventLatch = new CountDownLatch(1);

            SslConfig sslConf = sslConfig();
            sslConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            sslConf.setAppProtocolSelector((channel, protocols) -> "http/2");

            // Server stack: SSL → String, with user event listener
            ProtoInitializer serverStack = ctx -> {
                ctx.addLast("SSL", new SslDuplexer(sslConf));
                // String codec with SslEvent listener
                ctx.addLast("string", new ProtoDuplexer<ByteBuf, String, String, ByteBuf>() {
                    @Override
                    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
                    }

                    @Override
                    public void onActive(ProtoContext context) {
                    }

                    @Override
                    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) {
                        if (event.getEventType() == SslHandshakeEvent.class) {
                            capturedEvent.set((SslHandshakeEvent) event.getData());
                            eventLatch.countDown();
                        }
                        return true;
                    }

                    @Override
                    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<String> rcvDown, ProtoRcvQueue<String> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
                        if (isRcv) {
                            return doDecoder1(context, rcvUp, rcvDown);
                        } else {
                            return doEncoder1(context, sndUp, sndDown);
                        }
                    }

                    @Override
                    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                        return ProtoStatus.Next;
                    }

                    @Override
                    public void onClose(ProtoContext context) {
                    }
                });
            };

            SslConfig clientConf = sslConfig();
            clientConf.setAppProtocol(new String[] { "http/2", "http/1.1" });
            ProtoInitializer clientStack = createProtoStack(clientConf);

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverStack, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientStack, config);
            listen.waitAnyAccept();
            ThreadUtils.sleep(1000);

            boolean received = eventLatch.await(3, TimeUnit.SECONDS);
            assert received : "SslEvent should be received";
            assert capturedEvent.get() != null : "SslEvent should not be null";
            assert capturedEvent.get().getContext() != null : "SslContext should be available";
            assert "http/2".equals(capturedEvent.get().getContext().getApplicationProtocol()) : "ALPN should be http/2";
        });
    }
}
