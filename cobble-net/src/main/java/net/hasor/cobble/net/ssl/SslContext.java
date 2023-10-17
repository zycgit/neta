package net.hasor.cobble.net.ssl;
public interface SslContext {

    SslConfig getConfig();

    boolean isServer();

    boolean isClient();

    /**
     * Returns the name of the negotiated application-level protocol.
     * @return the application-level protocol name or {@code null} if the negotiation failed or the client does not have ALPN/NPN extension
     */
    String getApplicationProtocol();

    String getPeerHost();

    int getPeerPort();
}