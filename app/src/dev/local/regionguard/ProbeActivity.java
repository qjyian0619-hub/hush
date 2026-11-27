package dev.local.regionguard;

import android.app.Activity;
import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.LocaleList;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private JSONObject report;
    private TextView text;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setPadding(30,80,30,30); layout.setOrientation(LinearLayout.VERTICAL);
        text = new TextView(this); text.setTextSize(15); layout.addView(text);
        WebView web = new WebView(this); layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        report = new JSONObject();
        put("active", () -> System.getProperty("regionguard.active", "off"));
        put("locale", () -> Locale.getDefault().toLanguageTag());
        put("localeList", () -> LocaleList.getDefault().toLanguageTags());
        put("timezone", () -> TimeZone.getDefault().getID());
        put("zoneId", () -> java.time.ZoneId.systemDefault().getId());
        put("icuTimezone", () -> android.icu.util.TimeZone.getDefault().getID());
        put("native", () -> System.getProperty("regionguard.native", "unavailable"));
        put("resourceLocales", () -> getResources().getConfiguration().getLocales().toLanguageTags());
        put("resourceMcc", () -> getResources().getConfiguration().mcc);
        put("resourceMnc", () -> getResources().getConfiguration().mnc);
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        put("simCountry", () -> tm.getSimCountryIso()); put("simOperator", () -> tm.getSimOperator());
        put("simName", () -> tm.getSimOperatorName()); put("networkCountry", () -> tm.getNetworkCountryIso());
        put("networkOperator", () -> tm.getNetworkOperator()); put("simState", () -> tm.getSimState());
        put("hasSim", () -> tm.hasIccCard()); put("modems", () -> tm.getActiveModemCount());
        put("subscriptions", () -> String.valueOf(getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList()));
        put("cellInfo", () -> String.valueOf(tm.getAllCellInfo()));
        LocationManager lm = getSystemService(LocationManager.class);
        put("locationEnabled", () -> lm.isLocationEnabled());
        put("lastLocation", () -> String.valueOf(lm.getLastKnownLocation("network")));
        WifiManager wm = getSystemService(WifiManager.class);
        put("ssid", () -> wm.getConnectionInfo().getSSID());
        put("bssid", () -> wm.getConnectionInfo().getBSSID());
        put("wifiScans", () -> wm.getScanResults().size());
        put("locationEnabled", () -> getSystemService(LocationManager.class).isLocationEnabled());
        put("lastLocation", () -> String.valueOf(getSystemService(LocationManager.class).getLastKnownLocation("gps")));
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        put("ssid", () -> wifi.getConnectionInfo().getSSID()); put("bssid", () -> wifi.getConnectionInfo().getBSSID());
        put("wifiScans", () -> wifi.getScanResults().size());
        for (String key : new String[]{"gsm.sim.operator.iso-country", "gsm.sim.operator.numeric", "gsm.operator.iso-country",
                "persist.sys.timezone", "persist.sys.locale", "persist.sys.language", "persist.sys.country", "ro.oplus.regionmark"}) {
            put(key, () -> Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key));
        }
        web.getSettings().setJavaScriptEnabled(true); WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                String worker = "onmessage=()=>postMessage({language:navigator.language,languages:navigator.languages,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,offset:new Date().getTimezoneOffset()})";
                String setup = "window.__workerReport=null;try{const w=new Worker(URL.createObjectURL(new Blob(["+JSONObject.quote(worker)+"],{type:'text/javascript'})));w.onmessage=e=>{window.__workerReport=e.data;w.terminate()};w.postMessage(0)}catch(e){window.__workerReport={error:String(e)}}";
                web.evaluateJavascript(setup, ignored -> {});
                String script = "JSON.stringify({language:navigator.language,languages:navigator.languages,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,offset:new Date().getTimezoneOffset(),winterOffset:new Date('2026-01-15T12:00:00Z').getTimezoneOffset(),summerOffset:new Date('2026-07-15T12:00:00Z').getTimezoneOffset(),dateString:new Date('2026-11-26T12:00:00Z').toString(),worker:window.__workerReport})";
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                web.evaluateJavascript(script, value -> {
                    try { report.put("webview", new JSONObject(new org.json.JSONTokener(value).nextValue().toString())); }
                    catch (Exception e) { try {report.put("webviewError", String.valueOf(e));} catch (Exception ignored) {} }
                    persist();
                });
                }, 700);
            }
        });
        web.loadDataWithBaseURL("https://offline.invalid/", "<!doctype html><html><body><h2>Offline local test</h2><p>No external resources, accounts or Claude connections.</p></body></html>", "text/html", "UTF-8", null);
        persist();
    }
    interface Getter { Object get() throws Exception; }
    private void put(String key, Getter getter) { try {report.put(key, getter.get());} catch(Throwable e){try{report.put(key,"ERROR: "+e.getClass().getSimpleName());}catch(Exception ignored){}} }
    private void persist() { try {String json = report.toString(2); text.setText(json); Files.write(new File(getFilesDir(),"report.json").toPath(), json.getBytes(StandardCharsets.UTF_8));}catch(Exception e){text.setText(e.toString());} }
}
