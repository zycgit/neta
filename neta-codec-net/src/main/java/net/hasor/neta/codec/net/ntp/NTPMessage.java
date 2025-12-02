package net.hasor.neta.codec.net.ntp;

public abstract class NTPMessage {
    private byte    leapIndicator;      // 2bit
    private byte    version = 3;        // 3bit
    private NTPMode ntpMode = NTPMode.CLIENT; // 3bit
    private byte[]  authenticator;      // 96bit+

    public byte getLeapIndicator() {
        return leapIndicator;
    }

    public void setLeapIndicator(byte leapIndicator) {
        this.leapIndicator = leapIndicator;
    }

    public byte getVersion() {
        return version;
    }

    public void setVersion(byte version) {
        this.version = version;
    }

    public NTPMode getNtpMode() {
        return ntpMode;
    }

    public void setNtpMode(NTPMode ntpMode) {
        this.ntpMode = ntpMode;
    }

    public byte[] getAuthenticator() {
        return authenticator;
    }

    public void setAuthenticator(byte[] authenticator) {
        this.authenticator = authenticator;
    }
}
