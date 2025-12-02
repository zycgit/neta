package net.hasor.neta.codec.net.ntp;

public class NTPPacket extends NTPMessage {
    private int     stratum;            // 8bit，时钟的层数，定义了时钟的准确度。层数为1的时钟准确度最高，从1到15依次递减。
    private int     pollInterval;       // 8bit，轮询时间，即发送报文的最小间隔时间。
    private byte    precision;          // 8bit，本地时钟的精度。
    private long    rootDelay;          // 32bit，到主参考时钟的总往返延迟时间。
    private int     rootDispersion;     // 32bit，本地时钟相对于主参考时钟的最大误差。
    private int     referenceID;        // 32bit，参考标识符，标识特定参考时钟。
    private long    referenceTimestamp; // 64bit，本地时钟最后一次被设定或更新的时间。如果值为0表示本地时钟从未被同步过。
    private long    originateTimestamp; // 64bit，NTP报文离开源端时的本地时间。
    private long    receiveTimestamp;   // 64bit，NTP报文到达目的端的本地时间。
    private long    transmitTimestamp;  // 64bit，目的端应答报文离开服务器端的本地时间。

    public int getStratum() {
        return stratum;
    }

    public void setStratum(int stratum) {
        this.stratum = stratum;
    }

    public int getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(int pollInterval) {
        this.pollInterval = pollInterval;
    }

    public byte getPrecision() {
        return precision;
    }

    public void setPrecision(byte precision) {
        this.precision = precision;
    }

    public long getRootDelay() {
        return rootDelay;
    }

    public void setRootDelay(long rootDelay) {
        this.rootDelay = rootDelay;
    }

    public int getRootDispersion() {
        return rootDispersion;
    }

    public void setRootDispersion(int rootDispersion) {
        this.rootDispersion = rootDispersion;
    }

    public int getReferenceID() {
        return referenceID;
    }

    public void setReferenceID(int referenceID) {
        this.referenceID = referenceID;
    }

    public long getReferenceTimestamp() {
        return referenceTimestamp;
    }

    public void setReferenceTimestamp(long referenceTimestamp) {
        this.referenceTimestamp = referenceTimestamp;
    }

    public long getOriginateTimestamp() {
        return originateTimestamp;
    }

    public void setOriginateTimestamp(long originateTimestamp) {
        this.originateTimestamp = originateTimestamp;
    }

    public long getReceiveTimestamp() {
        return receiveTimestamp;
    }

    public void setReceiveTimestamp(long receiveTimestamp) {
        this.receiveTimestamp = receiveTimestamp;
    }

    public long getTransmitTimestamp() {
        return transmitTimestamp;
    }

    public void setTransmitTimestamp(long transmitTimestamp) {
        this.transmitTimestamp = transmitTimestamp;
    }

    @Override
    public String toString() {
        return "NTPPacket{" +
                "leapIndicator=" + getLeapIndicator() +
                ", version=" + getVersion() +
                ", ntpMode=" + getNtpMode() +
                ", stratum=" + stratum +
                ", pollInterval=" + pollInterval +
                ", precision=" + precision +
                ", rootDelay=" + rootDelay +
                ", rootDispersion=" + rootDispersion +
                ", referenceID=" + referenceID +
                ", referenceTimestamp=" + referenceTimestamp +
                ", originateTimestamp=" + originateTimestamp +
                ", receiveTimestamp=" + receiveTimestamp +
                ", transmitTimestamp=" + transmitTimestamp +
                '}';
    }
}
