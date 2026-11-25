package dev.local.regionguard;

import android.app.Activity;
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

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences preferences;
        boolean shared;
        try { preferences = getSharedPreferences(Profile.PREFS, MODE_WORLD_READABLE); shared = true; }
        catch (SecurityException e) { preferences = getSharedPreferences(Profile.PREFS, MODE_PRIVATE); shared = false; }
        final SharedPreferences prefs = preferences;
        if (!prefs.contains("zone")) prefs.edit().putString("zone", Profile.DEFAULT_ZONE).putBoolean("enabled", true).putBoolean("browsers", true).commit();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(36, 90, 36, 48);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        title(layout, "地区隐私");
        text(layout, "KernelSU 统一入口 · " + Profile.VERSION + "\nSIM / 运营商 / 定位 / Wi-Fi 扫描 / 语言 / 时区");
        text(layout, shared ? "配置共享正常。修改后，强行停止并重新打开目标应用。" : "配置共享尚未激活。请在 LSPosed 中启用模块后重新打开本页面。默认配置：英语 + 加州时区。");
        Switch enabled = new Switch(this); enabled.setText("启用保护"); enabled.setChecked(prefs.getBoolean("enabled", true)); layout.addView(enabled);
        Switch browsers = new Switch(this); browsers.setText("浏览器保护（语言 · 时区 · WebRTC · 定位）"); browsers.setChecked(prefs.getBoolean("browsers", true)); layout.addView(browsers);
        text(layout, "应用语言：English (en-US)\n时区（按地区处理夏令时）：");
        Spinner zones = new Spinner(this); zones.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, Profile.ZONE_LABELS));
        String selected = prefs.getString("zone", Profile.DEFAULT_ZONE);
        for (int i = 0; i < Profile.ZONES.length; i++) if (Profile.ZONES[i].equals(selected)) zones.setSelection(i);
        layout.addView(zones);
        Button save = new Button(this); save.setText("保存配置"); layout.addView(save);
        save.setOnClickListener(v -> {
            boolean ok = prefs.edit().putBoolean("enabled", enabled.isChecked()).putBoolean("browsers", browsers.isChecked()).putString("zone", Profile.ZONES[zones.getSelectedItemPosition()]).commit();
            Toast.makeText(this, ok ? "已保存，请重新打开目标应用。" : "保存失败", Toast.LENGTH_LONG).show();
        });
        text(layout, "在 KSU → 模块 → 地区隐私可打开 WebUI。\n\n浏览器保护作用于 LSPosed 勾选的浏览器。Chrome 和 Edge 每次启动时重设网页语言与定位权限，并禁止 WebRTC 绕过代理发送 UDP。Chrome 和 Edge 冷启动后第一个网页的时区由 Zygisk 部分修正，安装后重启一次生效。\n\n保存后，强行停止并重新打开目标应用。访问网站时由 VPN 接管流量。");
    }
    private void title(LinearLayout layout, String value) { TextView view = text(layout, value); view.setTextSize(30); }
    private TextView text(LinearLayout layout, String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(17); view.setPadding(0, 18, 0, 18); layout.addView(view); return view; }
}
