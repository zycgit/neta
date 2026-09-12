/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.net.ntp;
/**
 * NTP Modes as defined in RFC 1305 and RFC 5905.
 * <p>
 * The mode field in the NTP header indicates the association mode.
 * </p>
 */
public enum NTPMode {
    /** 0: Reserved */
    RESERVED(0),
    /** 1: Symmetric active */
    SYMMETRIC_ACTIVE(1),
    /** 2: Symmetric passive */
    SYMMETRIC_PASSIVE(2),
    /** 3: Client */
    CLIENT(3),
    /** 4: Server */
    SERVER(4),
    /** 5: Broadcast */
    BROADCAST(5),
    /** 6: NTP control message */
    CONTROL_MESSAGE(6),
    /** 7: Reserved for private use */
    PRIVATE_USE(7);

    private final int mode;

    /**
     * Constructs a new NTPMode.
     * @param mode the mode value
     */
    NTPMode(int mode) {
        this.mode = mode;
    }

    /**
     * Returns the NTPMode corresponding to the given mode value.
     * @param mode the mode value
     * @return the NTPMode, or null if not found
     */
    public static NTPMode fromMode(int mode) {
        for (NTPMode m : NTPMode.values()) {
            if (m.mode == mode) {
                return m;
            }
        }
        return null;
    }

    /**
     * Returns the mode value.
     * @return the mode value
     */
    public int getMode() {
        return mode;
    }
}
