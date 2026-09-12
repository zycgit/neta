/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
/**
 * Indicates the format of the cryptographic material (certificate and private key)
 * provided to a {@link SslConfig}.
 * <ul>
 *   <li>{@link #JKS}: Java KeyStore format ({@code .jks} / {@code .p12} file).
 *       The keystore and truststore are loaded by
 *       {@link java.security.KeyStore#getInstance(String)} and require a password.
 *       Standard for Java-only deployments.</li>
 *   <li>{@link #PEM}: Privacy Enhanced Mail format — Base64-encoded DER files
 *       commonly used by OpenSSL ({@code .pem}, {@code .crt}, {@code .key}).
 *       The framework parses the certificate chain with
 *       {@link java.security.cert.CertificateFactory} and the PKCS#8 private key
 *       via {@link SslUtils} (optionally delegating to
 *       {@link SslPemReaderByBouncyCastle} for encrypted keys when BouncyCastle
 *       is present on the classpath).</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SslContextBasic
 * @see SslUtils
 */
public enum SslAuthKeyType {
    JKS,
    PEM,
}
