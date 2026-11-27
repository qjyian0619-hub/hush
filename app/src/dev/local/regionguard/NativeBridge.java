package dev.local.regionguard;

public final class NativeBridge {
    public static native void configure(String timezone, String locale, String language, String country);
    public static native String probe();
    private NativeBridge() {}
}
