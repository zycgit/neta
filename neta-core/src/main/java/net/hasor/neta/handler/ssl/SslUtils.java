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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufInputStream;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * inner utils.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SslUtils {
    private static final Logger   logger               = Logger.getLogger(SslUtils.class);
    public static final  String[] DEFAULT_CIPHER_SUITES;
    public static final  String[] DEFAULT_TLSV13_CIPHER_SUITES;
    public static final  String[] TLSV13_CIPHER_SUITES = { "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384" };

    private static final boolean TLSV1_3_JDK_SUPPORTED;
    private static final boolean TLSV1_3_JDK_DEFAULT_ENABLED;

    private SslUtils() {
    }

    private static SSLContext newInitContext(Provider provider) throws GeneralSecurityException {
        final SSLContext context;
        if (provider == null) {
            context = SSLContext.getInstance("TLS");
        } else {
            context = SSLContext.getInstance("TLS", provider);
        }
        context.init(null, new TrustManager[0], null);
        return context;
    }

    private static boolean isTLSv13SupportedByJDK0(Provider provider) {
        try {
            return ArrayUtils.contains(newInitContext(provider).getSupportedSSLParameters().getProtocols(), SslProtocol.TLS_v1_3);
        } catch (Throwable cause) {
            if (logger.isDebugEnabled()) {
                logger.error("Unable to detect if JDK SSLEngine with provider " + provider + " supports TLSv1.3, assuming no", cause);
            }
            return false;
        }
    }

    private static boolean isTLSv13EnabledByJDK0(Provider provider) {
        try {
            return ArrayUtils.contains(newInitContext(provider).getDefaultSSLParameters().getProtocols(), SslProtocol.TLS_v1_3);
        } catch (Throwable cause) {
            if (logger.isDebugEnabled()) {
                logger.error("Unable to detect if JDK SSLEngine with provider " + provider + " enables TLSv1.3 by default, assuming no", cause);
            }
            return false;
        }
    }

    static {
        TLSV1_3_JDK_SUPPORTED = isTLSv13SupportedByJDK0(null);
        TLSV1_3_JDK_DEFAULT_ENABLED = isTLSv13EnabledByJDK0(null);
        if (TLSV1_3_JDK_SUPPORTED) {
            DEFAULT_TLSV13_CIPHER_SUITES = TLSV13_CIPHER_SUITES;
        } else {
            DEFAULT_TLSV13_CIPHER_SUITES = ArrayUtils.EMPTY_STRING_ARRAY;
        }

        Set<String> defaultCiphers;
        defaultCiphers = new LinkedHashSet<>();
        // GCM (Galois/Counter Mode) requires JDK 8.
        defaultCiphers.add("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384");
        defaultCiphers.add("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        defaultCiphers.add("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        defaultCiphers.add("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        defaultCiphers.add("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA");
        // AES256 requires JCE unlimited strength jurisdiction policy files.
        defaultCiphers.add("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA");
        // GCM (Galois/Counter Mode) requires JDK 8.
        defaultCiphers.add("TLS_RSA_WITH_AES_128_GCM_SHA256");
        defaultCiphers.add("TLS_RSA_WITH_AES_128_CBC_SHA");
        // AES256 requires JCE unlimited strength jurisdiction policy files.
        defaultCiphers.add("TLS_RSA_WITH_AES_256_CBC_SHA");

        Collections.addAll(defaultCiphers, DEFAULT_TLSV13_CIPHER_SUITES);

        DEFAULT_CIPHER_SUITES = defaultCiphers.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
    }

    public static KeyManagerFactory buildKeyManagerFactory(KeyStore keyStore, char[] passwordChars, KeyManagerFactory kmf) throws GeneralSecurityException, IOException {
        // Set up key manager factory to use our key store
        if (kmf == null) {
            String keyAlgorithm = KeyManagerFactory.getDefaultAlgorithm();
            kmf = KeyManagerFactory.getInstance(keyAlgorithm);
        }
        kmf.init(keyStore, passwordChars);
        return kmf;
    }

    public static TrustManagerFactory buildTrustManagerFactory(KeyStore keyStore, TrustManagerFactory tmf) throws GeneralSecurityException, IOException {
        // Set up trust manager factory to use our key store.
        if (tmf == null) {
            String keyAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            tmf = TrustManagerFactory.getInstance(keyAlgorithm);
        }
        tmf.init(keyStore);

        return tmf;
    }

    /**
     * Generates a new {@link KeyStore}.
     * @param certChain a X.509 certificate chain
     * @param key a PKCS#8 private key
     * @param passwordChars the password of the {@code keyFile}. {@code null} if it's not password-protected.
     */
    public static void loadKeyStore(KeyStore keyStore, X509Certificate[] certChain, PrivateKey key, char[] passwordChars) throws GeneralSecurityException, IOException {
        Objects.requireNonNull(certChain, "required certChain");
        Objects.requireNonNull(key, "required key");
        keyStore.load(null, null);

        int i = 1;
        for (X509Certificate cert : certChain) {
            keyStore.setCertificateEntry(("CRT_" + i), cert);
            i++;
        }
        keyStore.setKeyEntry("key", key, passwordChars, certChain);
    }

    /**
     * Generates a new {@link KeyStore}.
     * @param jksInputStream a JKS file InputStream
     * @param passwordChars the password of the {@code keyFile}. {@code null} if it's not password-protected.
     * @return generated {@link KeyStore}.
     */
    public static void loadKeyStore(KeyStore keyStore, InputStream jksInputStream, char[] passwordChars) throws GeneralSecurityException, IOException {
        keyStore.load(jksInputStream, passwordChars);
    }

    /** Read the certificate chain in a CER file */
    public static X509Certificate[] toX509Certificates(InputStream cerInputStream) throws CertificateException {
        if (cerInputStream == null) {
            return null;
        }

        ByteBuf[] certs = SslPemReader.readCertificates(cerInputStream);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate[] x509Certs = new X509Certificate[certs.length];

        try {
            for (int i = 0; i < certs.length; i++) {
                InputStream is = new ByteBufInputStream(certs[i]);
                try {
                    x509Certs[i] = (X509Certificate) cf.generateCertificate(is);
                } finally {
                    IOUtils.closeQuietly(is);
                }
            }
        } finally {
            for (ByteBuf buf : certs) {
                buf.free();
            }
        }
        return x509Certs;
    }

    /** Convert the private key defined in PEM file to {@link PrivateKey} */
    public static PrivateKey toPrivateKey(InputStream pemInputStream, String keyPassword) throws GeneralSecurityException, IOException {
        if (pemInputStream == null) {
            return null;
        }

        // bouncycastle is preferred for key parsing because it is the richest, and falls back to the original way parsing if it fails
        if (SslPemReaderByBouncyCastle.isAvailable()) {
            if (!pemInputStream.markSupported()) {
                // We need an input stream that supports resetting, in case BouncyCastle fails to read.
                pemInputStream = new BufferedInputStream(pemInputStream);
            }
            pemInputStream.mark(1048576); // Be able to reset up to 1 MiB of data.
            PrivateKey pk = SslPemReaderByBouncyCastle.getPrivateKey(pemInputStream, keyPassword);
            if (pk != null) {
                return pk;
            }
            // BouncyCastle could not read the key. Reset the input stream in case the input position changed.
            pemInputStream.reset();
        }

        //
        ByteBuf encodedKeyBuf = SslPemReader.readPrivateKey(pemInputStream);
        byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
        encodedKeyBuf.readBytes(encodedKey);
        encodedKeyBuf.free();

        PKCS8EncodedKeySpec encodedKeySpec = generateKeySpec(keyPassword == null ? null : keyPassword.toCharArray(), encodedKey);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec);
        } catch (InvalidKeySpecException ignore) {
            try {
                return KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec);
            } catch (InvalidKeySpecException ignore2) {
                try {
                    return KeyFactory.getInstance("EC").generatePrivate(encodedKeySpec);
                } catch (InvalidKeySpecException e) {
                    throw new InvalidKeySpecException("Neither RSA, DSA nor EC worked", e);
                }
            }
        }
    }

    private static PKCS8EncodedKeySpec generateKeySpec(char[] password, byte[] key) throws GeneralSecurityException, IOException {
        if (password == null) {
            return new PKCS8EncodedKeySpec(key);
        }

        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(key);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
        PBEKeySpec pbeKeySpec = new PBEKeySpec(password);
        SecretKey pbeKey = keyFactory.generateSecret(pbeKeySpec);

        Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
        cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedPrivateKeyInfo.getAlgParameters());

        return encryptedPrivateKeyInfo.getKeySpec(cipher);
    }
}