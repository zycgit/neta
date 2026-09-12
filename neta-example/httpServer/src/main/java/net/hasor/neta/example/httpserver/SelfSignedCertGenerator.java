/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.example.httpserver;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.security.*;
import java.util.Date;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates TLS certificates for development use with a local Root CA hierarchy.
 * <p>
 * On first run, creates a Root CA key pair and certificate stored in {@code src/main/resources/ssl/}.
 * The CA certificate needs to be trusted once in the operating system's trust store.
 * Subsequent runs reuse the existing CA to sign new server certificates.
 * <p>
 * Certificate hierarchy:
 * <pre>
 *   Root CA  (src/main/resources/ssl/neta-ca.crt, valid 10 years, persistent)
 *   └── Server cert (src/main/resources/ssl/server.crt, regenerated each startup, valid 1 year)
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 */
public class SelfSignedCertGenerator {

    private static final File SSL_DIR      = resolveClasspathSslDir();
    private static final File CA_CERT_FILE = new File(SSL_DIR, "neta-ca.crt");
    private static final File CA_KEY_FILE  = new File(SSL_DIR, "neta-ca.pem");

    /** Result holder for generated certificate file paths */
    public static class CertFiles {
        public final File    certFile;  // server certificate chain (server cert + CA cert, PEM)
        public final File    keyFile;   // server private key (PEM)
        public final File    caFile;    // root CA certificate (PEM)
        public final boolean caCreated; // true if CA was newly created (first run)

        CertFiles(File certFile, File keyFile, File caFile, boolean caCreated) {
            this.certFile = certFile;
            this.keyFile = keyFile;
            this.caFile = caFile;
            this.caCreated = caCreated;
        }
    }

    /**
     * Returns existing server certificate files if they are already present; otherwise generates
     * a new server certificate signed by the persistent local Root CA (creating the CA on first run).
     * <p>
     * To force certificate renewal, call {@link #renew(String)} explicitly.
     * @param hostname the hostname for the server certificate SAN (e.g., "localhost")
     * @return CertFiles containing paths to the cert, key, and CA files
     * @throws Exception if certificate generation fails
     */
    public static CertFiles generate(String hostname) throws Exception {
        File certFile = new File(SSL_DIR, "server.crt");
        File keyFile = new File(SSL_DIR, "server.pem");
        if (certFile.exists() && keyFile.exists() && CA_CERT_FILE.exists()) {
            return new CertFiles(certFile, keyFile, CA_CERT_FILE, false);
        }
        return renew(hostname);
    }

    /**
     * (Re)generates the server certificate signed by the persistent local Root CA, overwriting any
     * existing server certificate files. Use this as a one-time utility to refresh certificates.
     * <p>
     * The Root CA is created only when it does not already exist.
     * @param hostname the hostname for the server certificate SAN (e.g., "localhost")
     * @return CertFiles containing paths to the refreshed cert, key, and CA files
     * @throws Exception if certificate generation fails
     */
    public static CertFiles renew(String hostname) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(2048, new SecureRandom());

        // --- Step 1: Load or create Root CA ---
        boolean caCreated = false;
        PrivateKey caPrivateKey;
        X509CertificateHolder caCertHolder;

        SSL_DIR.mkdirs();
        if (CA_CERT_FILE.exists() && CA_KEY_FILE.exists()) {
            // Reuse existing CA
            try (PEMParser parser = new PEMParser(new FileReader(CA_CERT_FILE))) {
                caCertHolder = (X509CertificateHolder) parser.readObject();
            }
            caPrivateKey = readPrivateKey(CA_KEY_FILE);
        } else {
            // Create new Root CA (first run)
            caCreated = true;

            KeyPair caKeyPair = keyGen.generateKeyPair();
            caPrivateKey = caKeyPair.getPrivate();

            long now = System.currentTimeMillis();
            X500Name caSubject = new X500Name("CN=Neta Dev CA, O=Neta Framework, C=CN");

            X509v3CertificateBuilder caBuilder = new JcaX509v3CertificateBuilder(//
                    caSubject, BigInteger.valueOf(now), //
                    new Date(now), new Date(now + 10L * 365 * 24 * 60 * 60 * 1000), // 10 years
                    caSubject, caKeyPair.getPublic());
            caBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            caBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("BC").build(caPrivateKey);
            caCertHolder = caBuilder.build(signer);

            writePem(CA_CERT_FILE, caCertHolder);
            writePem(CA_KEY_FILE, caKeyPair.getPrivate());
        }

        // --- Step 2: Generate server certificate signed by CA ---
        KeyPair serverKeyPair = keyGen.generateKeyPair();
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now);
        Date notAfter = new Date(now + 365L * 24 * 60 * 60 * 1000); // 1 year

        X500Name issuer = caCertHolder.getSubject();
        X500Name serverSubject = new X500Name("CN=" + hostname + ", O=Neta Framework, C=CN");

        X509v3CertificateBuilder serverBuilder = new JcaX509v3CertificateBuilder(//
                issuer, BigInteger.valueOf(now + 1), notBefore, notAfter, //
                serverSubject, serverKeyPair.getPublic());

        // Subject Alternative Names — required by Chrome
        GeneralNames sans = new GeneralNames(new GeneralName[] {//
                new GeneralName(GeneralName.dNSName, hostname), //
                new GeneralName(GeneralName.dNSName, "localhost"), //
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"), //
                new GeneralName(GeneralName.iPAddress, "::1") });
        serverBuilder.addExtension(Extension.subjectAlternativeName, false, sans);
        serverBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        serverBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        serverBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

        ContentSigner serverSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("BC").build(caPrivateKey);
        X509CertificateHolder serverCertHolder = serverBuilder.build(serverSigner);

        // --- Step 3: Write server cert chain and private key alongside CA ---
        File certFile = new File(SSL_DIR, "server.crt");
        File keyFile = new File(SSL_DIR, "server.pem");

        // Server cert chain: server cert + CA cert (leaf first)
        try (Writer writer = new FileWriter(certFile); JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(serverCertHolder);
            pemWriter.writeObject(caCertHolder);
        }

        // Server private key
        try (Writer writer = new FileWriter(keyFile); JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(serverKeyPair.getPrivate());
        }

        return new CertFiles(certFile, keyFile, CA_CERT_FILE, caCreated);
    }

    /** Reads a PEM-encoded private key (supports both PKCS#1 and PKCS#8 formats) */
    private static PrivateKey readPrivateKey(File file) throws Exception {
        try (PEMParser parser = new PEMParser(new FileReader(file))) {
            Object keyObj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (keyObj instanceof PEMKeyPair) {
                return converter.getPrivateKey(((PEMKeyPair) keyObj).getPrivateKeyInfo());
            } else if (keyObj instanceof PrivateKeyInfo) {
                return converter.getPrivateKey((PrivateKeyInfo) keyObj);
            }
            throw new IllegalStateException("Unexpected PEM object type: " + keyObj.getClass().getName());
        }
    }

    /** Writes a certificate or key object as PEM to a file */
    private static void writePem(File file, Object obj) throws Exception {
        try (Writer writer = new FileWriter(file); JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(obj);
        }
    }

    /** Resolves src/main/resources/ssl/ under this module (survives mvn clean) */
    private static File resolveClasspathSslDir() {
        try {
            java.net.URL url = SelfSignedCertGenerator.class.getResource("/");
            if (url != null) {
                // target/classes/ -> target/ -> module-root/ -> src/main/resources/ssl/
                File classesDir = new File(url.toURI());
                File moduleRoot = classesDir.getParentFile().getParentFile();
                File resourcesSsl = new File(moduleRoot, "src/main/resources/ssl");
                if (moduleRoot.exists()) {
                    return resourcesSsl;
                }
            }
        } catch (Exception e) { /* ignore */ }
        return new File(System.getProperty("user.dir"), "src/main/resources/ssl");
    }
}
