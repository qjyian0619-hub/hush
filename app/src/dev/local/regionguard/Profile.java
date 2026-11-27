package dev.local.regionguard;

import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import android.content.SharedPreferences;

public final class Profile {
    public static final String PACKAGE = "dev.local.regionguard";
    public static final String PREFS = "profile";
    public static final String VERSION = "1.1.1";
    public static final String DEFAULT_ZONE = "America/Los_Angeles";
    public static final String DEFAULT_LANGUAGE = "en-US";
    public static final String[] ZONES = {
        DEFAULT_ZONE, "America/New_York", "Asia/Tokyo", "Asia/Taipei", "Asia/Singapore", "Europe/London", "UTC"
    };
    public final String zone;
    public final String language;
    public final Locale locale;
    public final boolean enabled;
    public final boolean browsers;
    public final boolean managed;
    public final boolean managerEnabled;
    public Profile(SharedPreferences preferences) {
        Map<String, ?> values = preferences.getAll();
        String candidate = (String) value(values, "zone", DEFAULT_ZONE);
        boolean valid = false;
        for (String item : TimeZone.getAvailableIDs()) if (item.equals(candidate)) {valid = true; break;}
        zone = valid ? candidate : DEFAULT_ZONE;
        language = languageForZone(zone);
        locale = Locale.forLanguageTag(language);
        enabled = (Boolean) value(values, "enabled", true);
        browsers = (Boolean) value(values, "browsers", true);
        managed = (Boolean) value(values, "ksu_managed", false);
        String boot = bootMarker();
        managerEnabled = managed && (Boolean) value(values, "ksu_enabled", false) && !boot.isEmpty()
            && boot.equals(value(values, "ksu_boot", ""));
    }

    private static String languageForZone(String zone) {
        switch (zone) {
            case "Asia/Tokyo": return "ja-JP";
            case "Asia/Taipei": return "zh-TW";
            case "Asia/Singapore": return "en-SG";
            case "Europe/London": return "en-GB";
            default: return DEFAULT_LANGUAGE;
        }
    }

    private static Object value(Map<String, ?> values, String key, Object fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value;
    }
    /**
     * Identifies the current boot, read the same way in the backend and in every hooked app. Apps may not
     * read ro.boottime.* (SELinux boottime_prop), so this uses system_server's start time, which is new on
     * every boot, or the kernel boot id. The source is part of the marker: two processes that can only
     * read different sources never match, so protection stays off instead of trusting a stale state.
     */
    public static String bootMarker() {
        try {
            String firstBoot = (String) Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String.class)
                .invoke(null, "ro.runtime.firstboot");
            if (firstBoot != null && firstBoot.matches("[0-9]{1,24}")) return "firstboot:" + firstBoot;
        } catch (Throwable ignored) {}
        try {
            String bootId = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")),
                java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (bootId.matches("[0-9a-f-]{36}")) return "boot_id:" + bootId;
        } catch (Throwable ignored) {}
        return "";
    }
}
