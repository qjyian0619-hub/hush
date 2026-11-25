package dev.local.regionguard;

public final class NativeBridge {
    public static native void configure(String timezone);
    public static native String probe();
    private NativeBridge() {}
}
