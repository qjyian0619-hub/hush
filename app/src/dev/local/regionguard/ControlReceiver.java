package dev.local.regionguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;
import io.github.libxposed.service.XposedService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/** DUMP is signature/privileged. Unprivileged apps cannot call this root control bridge. */
public final class ControlReceiver extends BroadcastReceiver {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        ProfileService.callStarted();
        WORKER.execute(() -> {
            try {
                execute(context, intent);
                result.setResultCode(0);
                result.setResultData("ok");
            } catch (Exception error) {
                result.setResultCode(1);
                result.setResultData(error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                try { result.finish(); }
                finally { ProfileService.callFinished(); }
            }
        });
    }

    static synchronized Profile execute(Context context, Intent intent) throws Exception {
        String operation = intent.getStringExtra("operation");
        if (!"save".equals(operation) && !"module-state".equals(operation) && !"status".equals(operation)) {
            throw new IllegalArgumentException("Unknown operation");
        }

        XposedService service = ProfileService.await();
        SharedPreferences prefs;
        try {
            prefs = service.getRemotePreferences(Profile.PREFS);
        } catch (XposedService.ServiceException | UnsupportedOperationException error) {
            throw ProfileService.discard(service, "Remote configuration read failed", error);
        }
        SharedPreferences.Editor edit = prefs.edit();
        File stateFile = new File(context.getFilesDir(), "ksu-state.json");
        if ("save".equals(operation)) {
            String zone = intent.getStringExtra("zone");
            if (!Arrays.asList(Profile.ZONES).contains(zone)) throw new IllegalArgumentException("Unsupported timezone");
            edit.putString("zone", zone).putBoolean("enabled", intent.getBooleanExtra("enabled", true))
                .putBoolean("browsers", intent.getBooleanExtra("browsers", true));
        } else if (!prefs.contains("zone")) {
            seedSettings(edit, stateFile);
        }

        String marker = Profile.bootMarker();
        if ("module-state".equals(operation)) {
            String shellBoot = intent.getStringExtra("boot");
            if (shellBoot == null || !shellBoot.matches("[0-9]{1,24}")) throw new IllegalArgumentException("Invalid boot marker");
            boolean enabled = intent.getBooleanExtra("enabled", false);
            // The shell's ro.boottime.init is unreadable for apps. Store the marker that hooked apps can read.
            if (enabled && marker.isEmpty()) throw new IllegalStateException("No boot marker readable by apps");
            edit.putBoolean("ksu_managed", true).putBoolean("ksu_enabled", enabled).putString("ksu_boot", marker);
        }

        boolean committed;
        // libxposed updates its local cache before the binder write. Recycle that cache on failure.
        try {
            committed = edit.commit();
        } catch (RuntimeException error) {
            throw ProfileService.discard(service, "Remote configuration save failed", error);
        }
        if (!committed) throw ProfileService.discard(service, "Remote configuration save failed", null);

        Profile profile = new Profile(prefs);
        ProfileService.confirmConnected(service);
        JSONObject state = new JSONObject().put("ok", true).put("version", Profile.VERSION).put("enabled", profile.enabled)
            .put("browsers", profile.browsers).put("zone", profile.zone).put("language", profile.language)
            .put("managerEnabled", profile.managerEnabled).put("sharedPreferences", true).put("managed", profile.managed)
            .put("bootSource", marker.isEmpty() ? "none" : marker.substring(0, marker.indexOf(':')));
        android.content.pm.LauncherApps launcher = context.getSystemService(android.content.pm.LauncherApps.class);
        state.put("launcherEntries", launcher.getActivityList(Profile.PACKAGE, android.os.Process.myUserHandle()).size());
        AtomicFile output = new AtomicFile(stateFile);
        FileOutputStream stream = output.startWrite();
        try {
            stream.write(state.toString().getBytes(StandardCharsets.UTF_8));
            output.finishWrite(stream);
        } catch (IOException error) {
            output.failWrite(stream);
            throw error;
        }
        return profile;
    }

    private static void seedSettings(SharedPreferences.Editor edit, File stateFile) {
        edit.putString("zone", Profile.DEFAULT_ZONE).putBoolean("enabled", true).putBoolean("browsers", true);
        if (!stateFile.isFile()) return;
        // Only releases through 1.8 used local preferences. The boot marker always comes from module-state.
        try {
            JSONObject previous = new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
            if (!previous.optString("version").matches("1\\.[0-8]") || !previous.optBoolean("ok")) return;
            String zone = previous.optString("zone");
            if (Arrays.asList(Profile.ZONES).contains(zone)) edit.putString("zone", zone);
            edit.putBoolean("enabled", previous.optBoolean("enabled", true))
                .putBoolean("browsers", previous.optBoolean("browsers", true));
        } catch (IOException | JSONException error) {
            Log.w("RegionGuard", "[RegionGuard] Previous profile could not be imported", error);
        }
    }
}
