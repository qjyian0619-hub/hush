package dev.local.regionguard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Chromium profile settings that web pages can observe. Used by the browser hook at every
 * browser start and by DeviceSetup, which keeps the PC-side backup used for restoring.
 */
final class BrowserPrefs {
    static final String PROFILE = "app_chrome/Default/Preferences";
    static final String[][] KEYS = {
        {"intl", "accept_languages"}, {"intl", "selected_languages"},
        {"webrtc", "ip_handling_policy"},
        {"profile", "default_content_setting_values", "geolocation"},
        {"profile", "default_content_setting_values", "sensors"}
    };
    private BrowserPrefs() {}

    /** Sets every key in parsed preferences. Returns the number of values changed. */
    static int apply(JSONObject preferences, Locale locale) throws Exception {
        String tag = locale.toLanguageTag();
        String base = locale.getLanguage();
        String languages = tag.equals(base) ? tag : tag + "," + base;
        Object[] values = {languages, languages, "disable_non_proxied_udp", 2, 2};
        int changed = 0;
        for (int i = 0; i < KEYS.length; i++) {
            JSONObject parent = parent(preferences, KEYS[i], true);
            String leaf = KEYS[i][KEYS[i].length - 1];
            if (values[i].equals(parent.opt(leaf))) continue;
            parent.put(leaf, values[i]);
            changed++;
        }
        return changed;
    }

    /**
     * Sets every key in the profile's Preferences file from inside the browser's own process, before
     * the browser reads it. The replacement file is created by that process, so it keeps the browser's
     * owner and SELinux label; root callers must use apply() and write in place instead.
     * Returns the number of values changed; 0 leaves the file untouched.
     */
    static int enforce(File file, Locale locale) throws Exception {
        JSONObject preferences = read(file);
        int changed = apply(preferences, locale);
        if (changed > 0) {
            // Same-directory rename keeps the replacement atomic if the process dies mid-write.
            File temporary = new File(file.getParentFile(), file.getName() + ".regionguard-tmp");
            write(temporary, preferences);
            if (!temporary.renameTo(file)) {
                temporary.delete();
                throw new IllegalStateException("cannot replace " + file);
            }
        }
        return changed;
    }

    static JSONObject parent(JSONObject root, String[] key, boolean create) throws Exception {
        JSONObject node = root;
        for (int i = 0; i < key.length - 1; i++) {
            JSONObject child = node.optJSONObject(key[i]);
            if (child == null) {
                if (!create) return null;
                child = new JSONObject();
                node.put(key[i], child);
            }
            node = child;
        }
        return node;
    }

    static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static void write(File file, JSONObject value) throws Exception {
        Files.write(file.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
