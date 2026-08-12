package com.termux.app;

public final class TermuxInstaller {

    static {
        System.loadLibrary("termux-bootstrap");
    }

    private TermuxInstaller() {
    }

    public static byte[] loadZipBytes() {
        return getZip();
    }

    public static native byte[] getZip();
}
