/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;
/**
 * Reflection bridge for JDK ALPN support on {@link javax.net.ssl.SSLEngine}.
 * <p>This helper resolves the ALPN-related JSSE methods once at class initialization and then exposes
 * a small wrapper API used by {@link JdkSslContext}. It covers application-protocol configuration,
 * negotiated-protocol lookup, and server-side selector installation.
 * <p><b>Compatibility:</b> the relevant methods were added in newer JDKs and some backported JDK 8
 * builds. {@link #supportsAlpn()} reports whether protocol lookup is available. The mutator methods in
 * this class are not documented as no-ops on unsupported runtimes; callers are expected to use them only
 * in environments where reflective initialization succeeded.
 * <p><b>Usage:</b> called internally by {@link JdkSslContext#configSslEngine(javax.net.ssl.SSLContext, SSLEngine)}
 * to install the application-protocol list or selector on the freshly created engine:
 * <pre>
 *   JdkAlpnSslUtils.setApplicationProtocols(engine, "h2", "http/1.1");
 *   // later, after handshake:
 *   String selected = JdkAlpnSslUtils.getApplicationProtocol(engine);
 * </pre>
 * <p>Adapted from {@code io.netty.handler.ssl.JdkAlpnSslUtils}.
 * @author Netty
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 * @see JdkSslContext
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7301">RFC 7301 — ALPN</a>
 */
class JdkAlpnSslUtils {
    private static final Logger logger = Logger.getLogger(JdkAlpnSslUtils.class);
    private static final Method SET_APPLICATION_PROTOCOLS;
    private static final Method GET_APPLICATION_PROTOCOL;
    private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL;
    private static final Method SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;
    private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;

    static {
        Method getHandshakeApplicationProtocol;
        Method getApplicationProtocol;
        Method setApplicationProtocols;
        Method setHandshakeApplicationProtocolSelector;
        Method getHandshakeApplicationProtocolSelector;

        try {
            SSLContext context = SSLContext.getInstance(JdkSslContext.PROTOCOL);
            context.init(null, null, null);
            SSLEngine engine = context.createSSLEngine();
            getHandshakeApplicationProtocol = SSLEngine.class.getMethod("getHandshakeApplicationProtocol");
            getHandshakeApplicationProtocol.invoke(engine);
            getApplicationProtocol = SSLEngine.class.getMethod("getApplicationProtocol");
            getApplicationProtocol.invoke(engine);
            setApplicationProtocols = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
            setApplicationProtocols.invoke(engine.getSSLParameters(), new Object[] { ArrayUtils.EMPTY_STRING_ARRAY });
            setHandshakeApplicationProtocolSelector = SSLEngine.class.getMethod("setHandshakeApplicationProtocolSelector", BiFunction.class);
            setHandshakeApplicationProtocolSelector.invoke(engine, (BiFunction<SSLEngine, List<String>, String>) (sslEngine, strings) -> {
                return null;
            });
            getHandshakeApplicationProtocolSelector = SSLEngine.class.getMethod("getHandshakeApplicationProtocolSelector");
            getHandshakeApplicationProtocolSelector.invoke(engine);
        } catch (Throwable t) {
            int version = SystemUtils.getJavaVersion();
            if (version >= 9) {
                // We only log when run on java9+ as this is expected on some earlier java8 versions
                logger.error("Unable to initialize JdkAlpnSslUtils, but the detected java version was: " + version, t);
            }
            getHandshakeApplicationProtocol = null;
            getApplicationProtocol = null;
            setApplicationProtocols = null;
            setHandshakeApplicationProtocolSelector = null;
            getHandshakeApplicationProtocolSelector = null;
        }
        GET_HANDSHAKE_APPLICATION_PROTOCOL = getHandshakeApplicationProtocol;
        GET_APPLICATION_PROTOCOL = getApplicationProtocol;
        SET_APPLICATION_PROTOCOLS = setApplicationProtocols;
        SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = setHandshakeApplicationProtocolSelector;
        GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = getHandshakeApplicationProtocolSelector;
    }

    private JdkAlpnSslUtils() {
    }

    public static boolean supportsAlpn() {
        return GET_APPLICATION_PROTOCOL != null;
    }

    public static String getApplicationProtocol(SSLEngine sslEngine) {
        try {
            return (String) GET_APPLICATION_PROTOCOL.invoke(sslEngine);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static void setApplicationProtocols(SSLEngine engine, String[] protocolArray) {
        SSLParameters parameters = engine.getSSLParameters();

        try {
            SET_APPLICATION_PROTOCOLS.invoke(parameters, new Object[] { protocolArray });
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        engine.setSSLParameters(parameters);
    }

    public static void setHandshakeApplicationProtocolSelector(SSLEngine engine, BiFunction<SSLEngine, List<String>, String> selector) {
        try {
            SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine, selector);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
