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
package net.hasor.neta.codec.net.ntp;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a standard NTP packet (v3 or v4).
 * <p>
 * This class contains the standard header fields defined in RFC 1305 (NTPv3) and RFC 5905 (NTPv4).
 * It also supports Extension Fields introduced in NTPv4.
 * </p>
 */
public class NTPPacket extends NTPMessage {
    private final List<NTPField> extensionFields = new ArrayList<>();

    /** Stratum (8 bits): Stratum level of the local clock. 1 is primary reference, 2-15 are secondary. */
    private int  stratum;
    /** Poll Interval (8 bits): Maximum interval between successive messages, in log2 seconds. */
    private int  pollInterval;
    /** Precision (8 bits): Precision of the local clock, in log2 seconds. */
    private byte precision;
    /** Root Delay (32 bits): Total roundtrip delay to the primary reference source, in fixed-point format. */
    private long rootDelay;
    /** Root Dispersion (32 bits): Maximum error due to the clock frequency tolerance, in fixed-point format. */
    private int  rootDispersion;
    /** Reference ID (32 bits): Identifier of the particular reference clock. */
    private int  referenceID;
    /** Reference Timestamp (64 bits): Time when the system clock was last set or corrected. */
    private long referenceTimestamp;
    /** Originate Timestamp (64 bits): Time at the client when the request departed for the server. */
    private long originateTimestamp;
    /** Receive Timestamp (64 bits): Time at the server when the request arrived from the client. */
    private long receiveTimestamp;
    /** Transmit Timestamp (64 bits): Time at the server when the response left for the client. */
    private long transmitTimestamp;

    /**
     * Returns the list of Extension Fields (NTPv4 only).
     * @return the list of extension fields
     */
    public List<NTPField> getExtensionFields() {
        return extensionFields;
    }

    /**
     * Adds an Extension Field to the packet.
     * @param field the extension field to add
     */
    public void addExtensionField(NTPField field) {
        this.extensionFields.add(field);
    }

    /**
     * Returns the Stratum level.
     * @return the stratum
     */
    public int getStratum() {
        return stratum;
    }

    /**
     * Sets the Stratum level.
     * @param stratum the stratum to set
     */
    public void setStratum(int stratum) {
        this.stratum = stratum;
    }

    /**
     * Returns the Poll Interval.
     * @return the poll interval
     */
    public int getPollInterval() {
        return pollInterval;
    }

    /**
     * Sets the Poll Interval.
     * @param pollInterval the poll interval to set
     */
    public void setPollInterval(int pollInterval) {
        this.pollInterval = pollInterval;
    }

    /**
     * Returns the Precision.
     * @return the precision
     */
    public byte getPrecision() {
        return precision;
    }

    /**
     * Sets the Precision.
     * @param precision the precision to set
     */
    public void setPrecision(byte precision) {
        this.precision = precision;
    }

    /**
     * Returns the Root Delay.
     * @return the root delay
     */
    public long getRootDelay() {
        return rootDelay;
    }

    /**
     * Sets the Root Delay.
     * @param rootDelay the root delay to set
     */
    public void setRootDelay(long rootDelay) {
        this.rootDelay = rootDelay;
    }

    /**
     * Returns the Root Dispersion.
     * @return the root dispersion
     */
    public int getRootDispersion() {
        return rootDispersion;
    }

    /**
     * Sets the Root Dispersion.
     * @param rootDispersion the root dispersion to set
     */
    public void setRootDispersion(int rootDispersion) {
        this.rootDispersion = rootDispersion;
    }

    /**
     * Returns the Reference ID.
     * @return the reference ID
     */
    public int getReferenceID() {
        return referenceID;
    }

    /**
     * Sets the Reference ID.
     * @param referenceID the reference ID to set
     */
    public void setReferenceID(int referenceID) {
        this.referenceID = referenceID;
    }

    /**
     * Returns the Reference Timestamp.
     * @return the reference timestamp
     */
    public long getReferenceTimestamp() {
        return referenceTimestamp;
    }

    /**
     * Sets the Reference Timestamp.
     * @param referenceTimestamp the reference timestamp to set
     */
    public void setReferenceTimestamp(long referenceTimestamp) {
        this.referenceTimestamp = referenceTimestamp;
    }

    /**
     * Returns the Originate Timestamp.
     * @return the originate timestamp
     */
    public long getOriginateTimestamp() {
        return originateTimestamp;
    }

    /**
     * Sets the Originate Timestamp.
     * @param originateTimestamp the originate timestamp to set
     */
    public void setOriginateTimestamp(long originateTimestamp) {
        this.originateTimestamp = originateTimestamp;
    }

    /**
     * Returns the Receive Timestamp.
     * @return the receive timestamp
     */
    public long getReceiveTimestamp() {
        return receiveTimestamp;
    }

    /**
     * Sets the Receive Timestamp.
     * @param receiveTimestamp the receive timestamp to set
     */
    public void setReceiveTimestamp(long receiveTimestamp) {
        this.receiveTimestamp = receiveTimestamp;
    }

    /**
     * Returns the Transmit Timestamp.
     * @return the transmit timestamp
     */
    public long getTransmitTimestamp() {
        return transmitTimestamp;
    }

    /**
     * Sets the Transmit Timestamp.
     * @param transmitTimestamp the transmit timestamp to set
     */
    public void setTransmitTimestamp(long transmitTimestamp) {
        this.transmitTimestamp = transmitTimestamp;
    }

    @Override
    public String toString() {
        return "NTPPacket{\n" +                                               //
                "    leapIndicator=" + this.getLeapIndicator() + ",\n" +      //
                "    version=" + this.getVersion() + ",\n" +                  //
                "    ntpMode=" + this.getNtpMode() + ",\n" +                  //
                "    stratum=" + this.stratum + ",\n" +                       //
                "    pollInterval=" + this.pollInterval + ",\n" +             //
                "    precision=" + this.precision + ",\n" +                   //
                "    rootDelay=" + this.rootDelay + ",\n" +                   //
                "    rootDispersion=" + this.rootDispersion + ",\n" +         //
                "    referenceID=" + this.referenceID + ",\n" +               //
                "    referenceTimestamp=" + this.referenceTimestamp + ",\n" + //
                "    originateTimestamp=" + this.originateTimestamp + ",\n" + //
                "    receiveTimestamp=" + this.receiveTimestamp + ",\n" +     //
                "    transmitTimestamp=" + this.transmitTimestamp + "\n" +    //
                '}';
    }
}
