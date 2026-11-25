package dev.local.regionguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** DUMP is signature/privileged. Unprivileged apps cannot call this root control bridge. */
public final class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            execute(context,intent);
            setResultCode(0); setResultData("ok");
        } catch (Throwable error) {
            setResultCode(1); setResultData(error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }
    static void execute(Context context,Intent intent) throws Exception {
            SharedPreferences prefs = context.getSharedPreferences(Profile.PREFS, Context.MODE_WORLD_READABLE);
            String operation = intent.getStringExtra("operation");
            if ("save".equals(operation)) {
                String zone = intent.getStringExtra("zone");
                boolean valid = false;
                for (String item : Profile.ZONES) if (item.equals(zone)) valid = true;
                if (!valid) throw new IllegalArgumentException("Unsupported timezone");
                if (!prefs.edit().putString("zone",zone).putBoolean("enabled",intent.getBooleanExtra("enabled",true))
                        .putBoolean("browsers",intent.getBooleanExtra("browsers",true)).commit()) throw new IllegalStateException("Save failed");
            } else if ("module-state".equals(operation)) {
                String shellBoot = intent.getStringExtra("boot");
                if (shellBoot == null || !shellBoot.matches("[0-9]{1,24}")) throw new IllegalArgumentException("Invalid boot marker");
                boolean enabled = intent.getBooleanExtra("enabled",false);
                // The shell's ro.boottime.init is unreadable for apps. Store the marker that hooked apps can read.
                String boot = Profile.bootMarker();
                if (enabled && boot.isEmpty()) throw new IllegalStateException("No boot marker readable by apps");
                if (!prefs.edit().putBoolean("ksu_managed",true).putBoolean("ksu_enabled",enabled).putString("ksu_boot",boot).commit())
                    throw new IllegalStateException("Module state save failed");
            } else if (!"status".equals(operation)) throw new IllegalArgumentException("Unknown operation");
            Profile profile = new Profile(prefs);
            JSONObject state = new JSONObject().put("ok",true).put("version",Profile.VERSION).put("enabled",profile.enabled)
                .put("browsers",profile.browsers).put("zone",profile.zone).put("language",profile.language)
                .put("managerEnabled",profile.managerEnabled).put("sharedPreferences",true)
                .put("managed",prefs.getBoolean("ksu_managed",false));
            String marker = Profile.bootMarker();
            state.put("bootSource", marker.isEmpty() ? "none" : marker.substring(0, marker.indexOf(':')));
            android.content.pm.LauncherApps launcher = context.getSystemService(android.content.pm.LauncherApps.class);
            state.put("launcherEntries",launcher.getActivityList(Profile.PACKAGE,android.os.Process.myUserHandle()).size());
            Files.write(new File(context.getFilesDir(),"ksu-state.json").toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
    }
}
