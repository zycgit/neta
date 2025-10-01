package net.hasor.neta.channel;

import java.io.IOException;

public class SoException extends IOException {

    public SoException(String s) {
        super(s);
    }

    public SoException(String s, Throwable e) {
        super(s, e);
    }
}