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
