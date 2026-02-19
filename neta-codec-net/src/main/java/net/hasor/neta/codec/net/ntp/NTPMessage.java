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
/**
 * Abstract base class for NTP messages.
 * <p>
 * This class defines the common fields shared by standard NTP packets and control messages,
 * such as Leap Indicator, Version Number, Mode, and Authenticator.
 * </p>
 * @see NTPPacket
 * @see NTPControlPacket
 */
public abstract class NTPMessage {
    /** Leap Indicator (2 bits): Warns of an impending leap second to be inserted/deleted. */
    private byte    leapIndicator;
    /** Version Number (3 bits): NTP/SNTP version number. */
    private byte    version = 3;
    /** Mode (3 bits): NTP mode (e.g., Client, Server, Broadcast). */
    private NTPMode ntpMode = NTPMode.CLIENT;
    /** Authenticator (optional): Key ID and Message Digest. */
    private byte[]  authenticator;

    /**
     * Returns the Leap Indicator.
     * @return the leap indicator
     */
    public byte getLeapIndicator() {
        return leapIndicator;
    }

    /**
     * Sets the Leap Indicator.
     * @param leapIndicator the leap indicator to set
     */
    public void setLeapIndicator(byte leapIndicator) {
        this.leapIndicator = leapIndicator;
    }

    /**
     * Returns the Version Number.
     * @return the version number
     */
    public byte getVersion() {
        return version;
    }

    /**
     * Sets the Version Number.
     * @param version the version number to set
     */
    public void setVersion(byte version) {
        this.version = version;
    }

    /**
     * Returns the NTP Mode.
     * @return the NTP mode
     */
    public NTPMode getNtpMode() {
        return ntpMode;
    }

    /**
     * Sets the NTP Mode.
     * @param ntpMode the NTP mode to set
     */
    public void setNtpMode(NTPMode ntpMode) {
        this.ntpMode = ntpMode;
    }

    /**
     * Returns the Authenticator.
     * @return the authenticator bytes, or null if not present
     */
    public byte[] getAuthenticator() {
        return authenticator;
    }

    /**
     * Sets the Authenticator.
     * @param authenticator the authenticator bytes to set
     */
    public void setAuthenticator(byte[] authenticator) {
        this.authenticator = authenticator;
    }
}
