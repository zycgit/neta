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
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.cobble.CollectionUtils;
import net.hasor.neta.channel.SoChannel;
/**
 * Shared certificate and ALPN configuration base class for all SSL/TLS providers
 * (JDK SSLEngine-based TLS/DTLS and QUIC custom TLS).
 * <p>
 * This class holds all certificate-related configuration, ALPN settings, and the
 * unified ALPN negotiation logic that is shared across all transport protocols.
 * <p>
 * ALPN negotiation priority chain:
 * <ol>
 *   <li>{@link #appProtocolSelector} — custom selector callback (highest priority)</li>
 *   <li>{@link #defaultAppProtocol} — if it appears in the intersection of local and peer lists</li>
 *   <li>First match in {@code appProtocol[] ∩ peerProtocols}</li>
 *   <li>{@code null} — negotiation failed</li>
 * </ol>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-01-01
 */
public class SslCertConfig {
    // ── Certificate material ───────────────────────────────────────────
    private SslAuthKeyType authType     = null;
    private String         jksResource  = null;     // JKS File
    private String         pemCertChain = null;     // X.509 certificate chain in PEM format.
    private String         pemPrivate   = null;     // PKCS#8 private key in PEM format.
    private String         keyPassword  = null;
    //
    // ── Direct certificate material (for QUIC or programmatic use) ────
    private X509Certificate[] certChainDirect  = null;
    private PrivateKey        privateKeyDirect = null;
    //
    // ── Pre-built SSL objects ──────────────────────────────────────────
    private SSLContext          sslContext          = null;
    private KeyStore            keyStore            = null;
    private KeyManagerFactory   keyManagerFactory   = null;
    private TrustManager[]      trustManagers;
    private TrustManagerFactory trustManagerFactory = null;
    //    // ── SNI configuration ───────────────────────────────────────────
    /** SNI server_name to send (client-side) or expected (server-side virtual hosting). */
    private String sniHostName = null;
    //    // ── ALPN configuration ─────────────────────────────────────────────
    /** Supported application-layer protocols for NPN/ALPN negotiation. */
    private String[]               appProtocol         = null;
    /** Default application-layer protocol when negotiation produces no match. */
    private String                 defaultAppProtocol  = null;
    /** Custom ALPN selector callback (highest priority). */
    private SslAppProtocolSelector appProtocolSelector = null;

    // ── Certificate material accessors ─────────────────────────────────

    public SslAuthKeyType getAuthType() {
        return this.authType;
    }

    public void setAuthType(SslAuthKeyType authType) {
        this.authType = authType;
    }

    public String getJksResource() {
        return this.jksResource;
    }

    public void setJksResource(String jksResource) {
        this.jksResource = jksResource;
    }

    public String getPemCertChain() {
        return this.pemCertChain;
    }

    public void setPemCertChain(String pemCertChain) {
        this.pemCertChain = pemCertChain;
    }

    public String getPemPrivate() {
        return this.pemPrivate;
    }

    public void setPemPrivate(String pemPrivate) {
        this.pemPrivate = pemPrivate;
    }

    public String getKeyPassword() {
        return this.keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    // ── Direct certificate material accessors ──────────────────────────

    public X509Certificate[] getCertChainDirect() {
        return this.certChainDirect;
    }

    public void setCertChainDirect(X509Certificate[] certChainDirect) {
        this.certChainDirect = certChainDirect;
    }

    public PrivateKey getPrivateKeyDirect() {
        return this.privateKeyDirect;
    }

    public void setPrivateKeyDirect(PrivateKey privateKeyDirect) {
        this.privateKeyDirect = privateKeyDirect;
    }

    // ── Pre-built SSL objects accessors ─────────────────────────────────

    public SSLContext getSslContext() {
        return this.sslContext;
    }

    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    public KeyStore getKeyStore() {
        return this.keyStore;
    }

    public void setKeyStore(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public KeyManagerFactory getKeyManagerFactory() {
        return this.keyManagerFactory;
    }

    public void setKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keyManagerFactory = keyManagerFactory;
    }

    public TrustManager[] getTrustManagers() {
        return this.trustManagers;
    }

    public void setTrustManagers(TrustManager[] trustManagers) {
        this.trustManagers = trustManagers;
    }

    public TrustManagerFactory getTrustManagerFactory() {
        return this.trustManagerFactory;
    }

    public void setTrustManagerFactory(TrustManagerFactory trustManagerFactory) {
        this.trustManagerFactory = trustManagerFactory;
    }
    // ── SNI configuration accessors ─────────────────────────────────

    /**
     * Returns the SNI server_name.
     * <p>Client-side: the host name to advertise in the TLS ClientHello SNI extension.
     * <p>Server-side: can be used for virtual hosting or certificate selection.
     * @return the configured SNI host name, or {@code null}
     */
    public String getSniHostName() {
        return this.sniHostName;
    }

    /**
     * Sets the SNI server_name.
     * <p>Client-side: will be sent in the TLS ClientHello SNI extension.
     * If not set, the SSLEngine's peer host will be used as fallback.
     * @param sniHostName the SNI host name to use
     */
    public void setSniHostName(String sniHostName) {
        this.sniHostName = sniHostName;
    }
    // ── ALPN configuration accessors ───────────────────────────────────

    public String[] getAppProtocol() {
        return this.appProtocol;
    }

    public void setAppProtocol(String[] appProtocol) {
        this.appProtocol = appProtocol;
    }

    public String getDefaultAppProtocol() {
        return this.defaultAppProtocol;
    }

    public void setDefaultAppProtocol(String defaultAppProtocol) {
        this.defaultAppProtocol = defaultAppProtocol;
    }

    public SslAppProtocolSelector getAppProtocolSelector() {
        return this.appProtocolSelector;
    }

    public void setAppProtocolSelector(SslAppProtocolSelector appProtocolSelector) {
        this.appProtocolSelector = appProtocolSelector;
    }

    // ── ALPN negotiation logic ─────────────────────────────────────────

    /**
     * Resolves the default application protocol.
     * <p>
     * If {@link #defaultAppProtocol} is set and appears in the configured
     * {@link #appProtocol} list (or if no appProtocol list is set), returns it.
     * Otherwise returns {@code null}.
     * @return the default protocol name, or {@code null}
     */
    public String resolveDefaultProtocol() {
        if (this.defaultAppProtocol == null) {
            return null;
        }
        if (this.appProtocol == null || this.appProtocol.length == 0) {
            return this.defaultAppProtocol;
        }
        for (String p : this.appProtocol) {
            if (this.defaultAppProtocol.equals(p)) {
                return this.defaultAppProtocol;
            }
        }
        return null;
    }

    /**
     * Performs unified ALPN negotiation using the standard priority chain.
     * <p>
     * Priority:
     * <ol>
     *   <li>{@link #appProtocolSelector} — if set, it decides (may return {@code null})</li>
     *   <li>{@link #defaultAppProtocol} — if it appears in both local list and peer list</li>
     *   <li>First match in {@code appProtocol[] ∩ peerProtocols}</li>
     *   <li>{@code null} — no match</li>
     * </ol>
     * @param channel the channel context (passed to the selector)
     * @param peerProtocols the list of protocols advertised by the peer
     * @return the negotiated protocol name, or {@code null} if no agreement
     */
    public String negotiateAlpn(SoChannel<?> channel, List<String> peerProtocols) {
        // 1. Custom selector has highest priority
        if (this.appProtocolSelector != null) {
            return this.appProtocolSelector.selector(channel, peerProtocols);
        }

        if (CollectionUtils.isEmpty(peerProtocols)) {
            return resolveDefaultProtocol();
        }

        // Build local protocol set for fast lookup
        Set<String> localSet;
        if (this.appProtocol != null && this.appProtocol.length > 0) {
            localSet = new LinkedHashSet<>(Arrays.asList(this.appProtocol));
        } else {
            // No local list configured — accept whatever peer offers
            // but check default first
            if (this.defaultAppProtocol != null && peerProtocols.contains(this.defaultAppProtocol)) {
                return this.defaultAppProtocol;
            }
            return peerProtocols.get(0);
        }

        // 2. defaultAppProtocol — if it appears in both local and peer lists
        if (this.defaultAppProtocol != null && localSet.contains(this.defaultAppProtocol) && peerProtocols.contains(this.defaultAppProtocol)) {
            return this.defaultAppProtocol;
        }

        // 3. First match in intersection (preserving local order)
        for (String local : this.appProtocol) {
            if (peerProtocols.contains(local)) {
                return local;
            }
        }

        // 4. No match
        return null;
    }
}
