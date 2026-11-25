package dev.local.regionguard;

import java.util.Locale;
import java.util.TimeZone;
import android.content.SharedPreferences;

public final class Profile {
    public static final String PACKAGE = "dev.local.regionguard";
    public static final String PREFS = "profile";
    public static final String VERSION = "1.8";
    public static final String DEFAULT_ZONE = "America/Los_Angeles";
    public static final String[] ZONES = {DEFAULT_ZONE, "America/New_York", "Asia/Tokyo", "Europe/London", "UTC"};
    public static final String[] ZONE_LABELS = {"加州 / 洛杉矶（自动夏令时）", "美国东部 / 纽约", "日本 / 东京", "英国 / 伦敦", "UTC"};
    public final String zone;
    public final String language;
    public final Locale locale;
    public final boolean enabled;
    public final boolean browsers;
    public final boolean managerEnabled;
    public Profile(SharedPreferences preferences) {
        String candidate = preferences == null ? DEFAULT_ZONE : preferences.getString("zone", DEFAULT_ZONE);
        boolean valid = false;
        for (String item : TimeZone.getAvailableIDs()) if (item.equals(candidate)) {valid = true; break;}
        zone = valid ? candidate : DEFAULT_ZONE;
        language = "en-US";
        locale = Locale.forLanguageTag(language);
        enabled = preferences == null || preferences.getBoolean("enabled", true);
        browsers = preferences == null || preferences.getBoolean("browsers", true);
        boolean managed = preferences != null && preferences.getBoolean("ksu_managed", false);
        String boot = bootMarker();
        managerEnabled = managed && (preferences.getBoolean("ksu_enabled", false) && !boot.isEmpty()
            && boot.equals(preferences.getString("ksu_boot", "")));
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
