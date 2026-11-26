package dev.local.regionguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private Runnable recoveryNotice;
    private Profile loadedProfile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // A draft restores form controls after a reconnect. The backend only reads remote preferences.
        SharedPreferences draft = getPreferences(MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(36, 90, 36, 48);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        title(layout, "地区隐私");
        text(layout, "KernelSU 统一入口 · " + Profile.VERSION + "\nSIM / 运营商 / 定位 / Wi-Fi 扫描 / 语言 / 时区");
        TextView status = text(layout, "正在连接 LSPosed 配置服务…");
        Switch enabled = new Switch(this); enabled.setText("启用保护"); enabled.setEnabled(false); layout.addView(enabled);
        Switch browsers = new Switch(this); browsers.setText("浏览器保护（语言 · 时区 · WebRTC · 定位）"); browsers.setEnabled(false); layout.addView(browsers);
        text(layout, "应用语言：English (en-US)\n时区（按地区处理夏令时）：");
        Spinner zones = new Spinner(this); zones.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, Profile.ZONE_LABELS));
        zones.setEnabled(false);
        layout.addView(zones);
        Button save = new Button(this); save.setText("保存配置"); save.setEnabled(false); layout.addView(save);
        execute(new Intent().putExtra("operation", "status"), profile -> {
            loadedProfile = profile;
            enabled.setChecked(draft.getBoolean("draftEnabled", profile.enabled));
            browsers.setChecked(draft.getBoolean("draftBrowsers", profile.browsers));
            String zone = draft.getString("draftZone", profile.zone);
            for (int i = 0; i < Profile.ZONES.length; i++) if (Profile.ZONES[i].equals(zone)) zones.setSelection(i);
            status.setText(draft.contains("draftZone") ? "已恢复上次未保存的选择。确认后可重新保存。"
                : "配置共享正常。修改后，强行停止并重新打开目标应用。");
            enabled.setEnabled(true);
            browsers.setEnabled(true);
            zones.setEnabled(true);
            save.setEnabled(true);
        }, error -> status.setText("无法连接 LSPosed 配置服务。请在 LSPosed 中启用模块后重新打开本页面。"));
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            Intent request = new Intent().putExtra("operation", "save").putExtra("enabled", enabled.isChecked())
                .putExtra("browsers", browsers.isChecked()).putExtra("zone", Profile.ZONES[zones.getSelectedItemPosition()]);
            execute(request, profile -> {
                loadedProfile = profile;
                draft.edit().clear().apply();
                status.setText("配置共享正常。修改后，强行停止并重新打开目标应用。");
                save.setEnabled(true);
                Toast.makeText(this, "已保存，请重新打开目标应用。", Toast.LENGTH_LONG).show();
            }, error -> {
                save.setEnabled(true);
                Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            });
        });
        recoveryNotice = () -> {
            String message = "配置服务已断开。设置页将关闭，重新打开时会自动连接。";
            int selection = zones.getSelectedItemPosition();
            if (loadedProfile != null && selection >= 0) {
                String zone = Profile.ZONES[selection];
                if (!zone.equals(loadedProfile.zone) || enabled.isChecked() != loadedProfile.enabled
                        || browsers.isChecked() != loadedProfile.browsers) {
                    if (draft.edit().putString("draftZone", zone).putBoolean("draftEnabled", enabled.isChecked())
                            .putBoolean("draftBrowsers", browsers.isChecked()).commit()) {
                        message += "未保存的选择已保留。";
                    }
                } else {
                    draft.edit().clear().apply();
                }
            }
            save.setEnabled(false);
            status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        };
        text(layout, "在 KSU → 模块 → 地区隐私可打开 WebUI。\n\n浏览器保护作用于 LSPosed 勾选的浏览器。Chrome 和 Edge 每次启动时重设网页语言与定位权限，并禁止 WebRTC 绕过代理发送 UDP。Chrome 和 Edge 冷启动后第一个网页的时区由 Zygisk 部分修正，首次安装该部分后重启一次生效。\n\n保存后，强行停止并重新打开目标应用。访问网站时由 VPN 接管流量。");
    }

    @Override protected void onStart() {
        super.onStart();
        ProfileService.setVisibleNotice(recoveryNotice, true);
    }

    @Override protected void onStop() {
        ProfileService.setVisibleNotice(recoveryNotice, false);
        super.onStop();
    }

    private void execute(Intent request, Consumer<Profile> success, Consumer<Exception> failure) {
        ProfileService.callStarted();
        ControlReceiver.WORKER.execute(() -> {
            try {
                Profile profile = ControlReceiver.execute(getApplicationContext(), request);
                complete(() -> success.accept(profile));
            } catch (Exception error) {
                complete(() -> failure.accept(error));
            }
        });
    }

    private void complete(Runnable response) {
        runOnUiThread(() -> {
            try { if (!isDestroyed()) response.run(); }
            finally { ProfileService.callFinished(); }
        });
    }
    private void title(LinearLayout layout, String value) { TextView view = text(layout, value); view.setTextSize(30); }
    private TextView text(LinearLayout layout, String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(17); view.setPadding(0, 18, 0, 18); layout.addView(view); return view; }
}
