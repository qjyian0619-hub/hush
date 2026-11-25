package dev.local.regionguard;

import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Invoked manually with root app_process by setup.ps1. Never runs on boot. Records the original
 * values for restoring; while browser protection is on, the browser hook re-applies them at every start.
 */
public final class DeviceSetup {
    private static final String[][] PREF_FILES = {
        {"com.android.chrome", "/data/user/0/com.android.chrome/" + BrowserPrefs.PROFILE},
        {"com.microsoft.emmx", "/data/user/0/com.microsoft.emmx/" + BrowserPrefs.PROFILE}
    };
    private static final String[][] KEYS = BrowserPrefs.KEYS;
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("apply") || args[0].equals("restore"))) throw new IllegalArgumentException("apply|restore BACKUP_FILE");
        File backupFile = new File(args[1]);
        JSONObject backup = backupFile.exists() ? BrowserPrefs.read(backupFile) : new JSONObject();
        for (String[] entry : PREF_FILES) {
            File file = new File(entry[1]);
            if (!file.exists()) {System.out.println(entry[0] + ": no existing preferences; skipped"); continue;}
            JSONObject preferences = BrowserPrefs.read(file);
            JSONArray records = backup.optJSONArray(entry[1]);
            if (args[0].equals("apply")) {
                if (records == null) {
                    records = new JSONArray();
                    for (String[] key : KEYS) {
                        JSONObject parent = BrowserPrefs.parent(preferences, key, false);
                        String leaf = key[key.length-1];
                        JSONObject record = new JSONObject().put("present", parent != null && parent.has(leaf));
                        if (parent != null && parent.has(leaf)) record.put("value", parent.get(leaf));
                        records.put(record);
                    }
                    backup.put(entry[1], records);
                    BrowserPrefs.write(backupFile, backup); // Commit rollback data before any change.
                }
                // In place, so the file keeps the browser's owner and SELinux label.
                BrowserPrefs.apply(preferences);
                BrowserPrefs.write(file, preferences);
                System.out.println(entry[0] + ": English, geolocation/sensors blocked, WebRTC policy set");
            } else if (records != null) {
                for (int i=0; i<KEYS.length; i++) {
                    JSONObject record = records.getJSONObject(i);
                    JSONObject parent = BrowserPrefs.parent(preferences,KEYS[i],record.getBoolean("present"));
                    if (parent != null) {
                        if (record.getBoolean("present")) parent.put(KEYS[i][KEYS[i].length-1],record.get("value"));
                        else parent.remove(KEYS[i][KEYS[i].length-1]);
                    }
                }
                BrowserPrefs.write(file, preferences);
                System.out.println(entry[0] + ": original preference values restored");
            }
        }
    }
}
