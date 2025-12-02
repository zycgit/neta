package net.hasor.neta.codec.net.ntp;

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

    NTPMode(int mode) {
        this.mode = mode;
    }

    public int getMode() {
        return mode;
    }

    public static NTPMode fromMode(int mode) {
        for (NTPMode m : NTPMode.values()) {
            if (m.mode == mode) {
                return m;
            }
        }
        return null;
    }
}
