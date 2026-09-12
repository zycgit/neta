/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
/**
 * Enumerates the SSL/TLS engine providers exposed by this package.
 * <p>At present only {@link #JSSE} is implemented by the runtime code in this package.
 * An OpenSSL-backed option was considered but remains commented out because:
 * <ul>
 *   <li>OpenSSL binding requires a native library (e.g., BoringSSL or OpenSSL 1.1+)
 *       that adds a mandatory JNI dependency, conflicting with the zero-native-dependency
 *       design goal.</li>
 *   <li>JSSE covers all required cipher suites and TLS versions (1.0 – 1.3) on
 *       modern JDKs (Oracle/OpenJDK ≥ 8u261 / 11.0.3) without native code.</li>
 * </ul>
 * <p>Usage:
 * <pre>
 *   SslConfig config = new SslConfig();
 *   config.setProvider(SslProvider.JSSE); // only supported value in this package today
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SslContextBasic
 */
public enum SslProvider {
    /**
     * JDK's default implementation.
     * see: <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html">JSSE Guide</a>,
     * <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html">JSSE TLS</a>
     */
    JSSE,
    //    /**
    //     * OpenSSL-based implementation.
    //     * see:
    //     */
    //    OPEN_SSL,
}
