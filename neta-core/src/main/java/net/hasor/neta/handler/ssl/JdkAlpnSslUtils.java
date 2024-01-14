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
package net.hasor.neta.handler.ssl;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.SystemUtils;
import net.hasor.cobble.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.function.BiFunction;

/**
 * source code from io.netty.handler.ssl.JdkAlpnSslUtils.
 * @version : 2023-10-18
 * @author Netty
 * @author 赵永春 (zyc@hasor.net)
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
            getHandshakeApplicationProtocol = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () -> {
                return SSLEngine.class.getMethod("getHandshakeApplicationProtocol");
            });
            getHandshakeApplicationProtocol.invoke(engine);
            getApplicationProtocol = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () -> {
                return SSLEngine.class.getMethod("getApplicationProtocol");
            });
            getApplicationProtocol.invoke(engine);
            setApplicationProtocols = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () -> {
                return SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
            });
            setApplicationProtocols.invoke(engine.getSSLParameters(), new Object[] { ArrayUtils.EMPTY_STRING_ARRAY });
            setHandshakeApplicationProtocolSelector = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () -> {
                return SSLEngine.class.getMethod("setHandshakeApplicationProtocolSelector", BiFunction.class);
            });
            setHandshakeApplicationProtocolSelector.invoke(engine, (BiFunction<SSLEngine, List<String>, String>) (sslEngine, strings) -> {
                return null;
            });
            getHandshakeApplicationProtocolSelector = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () -> {
                return SSLEngine.class.getMethod("getHandshakeApplicationProtocolSelector");
            });
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

    //    public static String getHandshakeApplicationProtocol(SSLEngine sslEngine) {
    //        try {
    //            return (String) GET_HANDSHAKE_APPLICATION_PROTOCOL.invoke(sslEngine);
    //        } catch (UnsupportedOperationException ex) {
    //            throw ex;
    //        } catch (Exception ex) {
    //            throw new IllegalStateException(ex);
    //        }
    //    }

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

    //    @SuppressWarnings("unchecked")
    //    public static BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector(SSLEngine engine) {
    //        try {
    //            return (BiFunction<SSLEngine, List<String>, String>) GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine);
    //        } catch (UnsupportedOperationException ex) {
    //            throw ex;
    //        } catch (Exception ex) {
    //            throw new IllegalStateException(ex);
    //        }
    //    }
}
