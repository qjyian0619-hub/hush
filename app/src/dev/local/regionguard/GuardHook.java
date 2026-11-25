package dev.local.regionguard;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.LocaleList;
import android.system.Os;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class GuardHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static String modulePath;
    private static boolean installed;
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList("com.android.chrome", "com.microsoft.emmx", "mark.via", "com.quark.browser"));
    private Profile profile;
    private int hookCount;

    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        // LSPosed scope is the explicit allowlist. Never hook critical system processes.
        if (installed || param.packageName.equals(Profile.PACKAGE) || param.appInfo == null || param.appInfo.uid < 10000
                || param.packageName.equals("com.android.systemui") || param.packageName.equals("com.android.settings")) return;
        XSharedPreferences preferences = null;
        try { preferences = new XSharedPreferences(Profile.PACKAGE, Profile.PREFS); preferences.reload(); }
        catch (Throwable ignored) {}
        profile = new Profile(preferences);
        if (profile.enabled && !profile.managerEnabled && param.isFirstApplication) {
            log("inactive package=" + param.packageName + ": KernelSU module state not synced for this boot (marker "
                + (Profile.bootMarker().isEmpty() ? "unreadable" : "mismatch") + ")");
        }
        if (!profile.enabled || !profile.managerEnabled || (BROWSERS.contains(param.packageName) && !profile.browsers)) return;
        installed = true;
        // Only the main browser process writes the profile, and it has not read it yet at this point.
        if (BROWSERS.contains(param.packageName) && param.packageName.equals(param.processName)) browserSettings(param.appInfo.dataDir);
        System.setProperty("regionguard.active", Profile.VERSION);
        System.setProperty("regionguard.zone", profile.zone);
        try {
            try { System.loadLibrary("regionguard"); }
            catch (UnsatisfiedLinkError first) { System.load(modulePath + "!/lib/arm64-v8a/libregionguard.so"); }
            NativeBridge.configure(profile.zone);
            System.setProperty("regionguard.native", NativeBridge.probe());
        } catch (Throwable error) { log("native unavailable: " + error); }
        localeHooks();
        telephonyHooks();
        locationHooks();
        wifiHooks();
        propertyHooks();
        browserHooks(param.classLoader);
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Context context = (Context) p.args[0];
                try {
                    Configuration configuration = context.getResources().getConfiguration();
                    configuration.setLocales(new LocaleList(profile.locale));
                    configuration.mcc = 0; configuration.mnc = 0;
                    context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
                } catch (Throwable ignored) {}
                log("active package=" + param.packageName + " process=" + param.processName + " hooks=" + hookCount
                    + " zone=" + TimeZone.getDefault().getID() + " locale=" + Locale.getDefault().toLanguageTag()
                    + " native=" + System.getProperty("regionguard.native", "unavailable"));
            }
        });
    }

    private void localeHooks() {
        TimeZone timezone = TimeZone.getTimeZone(profile.zone);
        TimeZone.setDefault(timezone);
        Locale.setDefault(profile.locale);
        LocaleList.setDefault(new LocaleList(profile.locale));
        System.setProperty("user.timezone", profile.zone);
        System.setProperty("user.language", "en");
        System.setProperty("user.country", "US");
        try { Os.setenv("TZ", profile.zone, true); } catch (Throwable ignored) {}
        for (String name : new String[]{"getDefault", "getDefaultRef"}) {
            hook(TimeZone.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(timezone.clone()); }
            });
        }
        constant(Locale.class, "getDefault", profile.locale);
        constant(LocaleList.class, "getDefault", new LocaleList(profile.locale));
        constant(LocaleList.class, "getAdjustedDefault", new LocaleList(profile.locale));
        constant(Configuration.class, "getLocales", new LocaleList(profile.locale));
        constant(android.icu.util.ULocale.class, "getDefault", android.icu.util.ULocale.forLocale(profile.locale));
        android.icu.util.TimeZone icuZone = android.icu.util.TimeZone.getTimeZone(profile.zone);
        try { XposedHelpers.callStaticMethod(android.icu.util.TimeZone.class, "setDefault", icuZone); } catch (Throwable ignored) {}
        hook(android.icu.util.TimeZone.class, "getDefault", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(icuZone.clone()); }
        });
        hook(Resources.class, "getConfiguration", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.getResult() instanceof Configuration)) return;
                Configuration copy = new Configuration((Configuration) p.getResult());
                copy.setLocales(new LocaleList(profile.locale)); copy.mcc = 0; copy.mnc = 0;
                p.setResult(copy);
            }
        });
        Class<?> localeManager = XposedHelpers.findClassIfExists("android.app.LocaleManager", null);
        constant(localeManager, "getSystemLocales", new LocaleList(profile.locale));
        constant(localeManager, "getApplicationLocales", new LocaleList(profile.locale));
        Class<?> timezoneGetter = XposedHelpers.findClassIfExists("libcore.util.TimezoneGetter", null);
        constant(timezoneGetter, "getId", profile.zone);
    }

    private void telephonyHooks() {
        for (String name : new String[]{"getSimCountryIso", "getSimCountryIsoForPhone", "getNetworkCountryIso", "getNetworkCountryIsoForPhone",
                "getSimOperator", "getSimOperatorNumeric", "getSimOperatorNumericForPhone", "getSimOperatorName", "getSimOperatorNameForPhone",
                "getNetworkOperator", "getNetworkOperatorForPhone", "getNetworkOperatorName", "getNetworkOperatorNameForPhone",
                "getSubscriberId", "getSimSerialNumber", "getLine1Number", "getLine1AlphaTag", "getVoiceMailNumber",
                "getDeviceId", "getImei", "getMeid", "getSimCarrierIdName", "getSimSpecificCarrierIdName", "getGroupIdLevel1", "getNai"}) {
            constant(TelephonyManager.class, name, "");
        }
        for (String name : new String[]{"getSimState", "getSimStateForSlotIndex"}) constant(TelephonyManager.class, name, TelephonyManager.SIM_STATE_ABSENT);
        for (String name : new String[]{"getPhoneCount", "getActiveModemCount"}) constant(TelephonyManager.class, name, 0);
        for (String name : new String[]{"getSimCarrierId", "getSimSpecificCarrierId"}) constant(TelephonyManager.class, name, TelephonyManager.UNKNOWN_CARRIER_ID);
        constant(TelephonyManager.class, "hasIccCard", false);
        constant(TelephonyManager.class, "getAllCellInfo", Collections.emptyList());
        constant(TelephonyManager.class, "getCellLocation", null);
        constant(TelephonyManager.class, "getServiceState", null);
        for (String name : new String[]{"getActiveSubscriptionInfoList", "getCompleteActiveSubscriptionInfoList", "getAccessibleSubscriptionInfoList",
                "getAvailableSubscriptionInfoList", "getAllSubscriptionInfoList", "getSubscriptionsInGroup"}) constant(SubscriptionManager.class, name, Collections.emptyList());
        for (String name : new String[]{"getActiveSubscriptionInfoCount", "getActiveSubscriptionInfoCountMax"}) constant(SubscriptionManager.class, name, 0);
        for (String name : new String[]{"getActiveSubscriptionInfo", "getActiveSubscriptionInfoForSimSlotIndex", "getActiveSubscriptionInfoForIccIndex"}) constant(SubscriptionManager.class, name, null);
        constant(SubscriptionManager.class, "getPhoneNumber", "");
        constant(SubscriptionManager.class, "getActiveSubscriptionIdList", new int[0]);
        for (String name : new String[]{"getCountryIso", "getMccString", "getMncString", "getNumber", "getIccId", "getCarrierName", "getDisplayName"}) constant(SubscriptionInfo.class, name, "");
        constant(SubscriptionInfo.class, "getMcc", 0);
        constant(SubscriptionInfo.class, "getMnc", 0);
    }

    private void locationHooks() {
        constant(LocationManager.class, "getLastKnownLocation", null);
        constant(LocationManager.class, "getAllProviders", Collections.emptyList());
        constant(LocationManager.class, "getProviders", Collections.emptyList());
        constant(LocationManager.class, "getBestProvider", null);
        constant(LocationManager.class, "isProviderEnabled", false);
        constant(LocationManager.class, "isLocationEnabled", false);
        for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate", "getCurrentLocation"}) {
            hook(LocationManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setThrowable(new SecurityException("Location access disabled by RegionGuard"));
                }
            });
        }
        constant(XposedHelpers.findClassIfExists("android.location.Geocoder", null), "isPresent", false);
    }

    private void wifiHooks() {
        constant(WifiInfo.class, "getSSID", "<unknown ssid>");
        constant(WifiInfo.class, "getBSSID", "02:00:00:00:00:00");
        constant(WifiInfo.class, "getMacAddress", "02:00:00:00:00:00");
        constant(WifiManager.class, "getScanResults", Collections.emptyList());
        constant(WifiManager.class, "getConfiguredNetworks", Collections.emptyList());
        constant(WifiManager.class, "getPrivilegedConfiguredNetworks", Collections.emptyList());
        constant(WifiManager.class, "startScan", false);
        constant(XposedHelpers.findClassIfExists("android.net.NetworkInfo", null), "getExtraInfo", "");
    }

    private String property(String key) {
        if (key.equals("persist.sys.timezone")) return profile.zone;
        if (key.equals("persist.sys.locale") || key.equals("ro.product.locale")) return "en-US";
        if (key.equals("persist.sys.language") || key.equals("ro.product.locale.language")) return "en";
        if (key.equals("persist.sys.country") || key.equals("ro.product.locale.region")) return "US";
        if (key.startsWith("gsm.sim.operator.") || key.startsWith("gsm.operator.") || key.startsWith("vendor.gsm.sim.operator.") || key.startsWith("vendor.gsm.operator.")) return "";
        if (key.equals("gsm.sim.state")) return "ABSENT,ABSENT";
        if (key.equals("ro.oplus.regionmark") || key.equals("ro.vendor.oplus.regionmark") || key.equals("persist.sys.oplus.region")
                || key.equals("persist.sys.oplus.regionmark") || key.equals("ro.miui.region") || key.equals("ro.csc.countryiso_code")) return "";
        return null;
    }

    private void propertyHooks() {
        XC_MethodHook localeSetting = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                for (Object argument : p.args) if ("system_locales".equals(argument)) {p.setResult("en-US");return;}
            }
        };
        hook(android.provider.Settings.System.class, "getString", localeSetting);
        hook(android.provider.Settings.System.class, "getStringForUser", localeSetting);
        hook(XposedHelpers.findClassIfExists("android.os.SystemProperties", null), "get", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof String) {
                    String replacement = property((String) p.args[0]);
                    if (replacement != null) p.setResult(replacement);
                }
            }
        });
    }

    /** Chromium browsers (Chrome, Edge): page languages, WebRTC IP handling, geolocation and sensors. */
    private static void browserSettings(String dataDir) {
        java.io.File file = new java.io.File(dataDir, BrowserPrefs.PROFILE);
        if (!file.isFile()) return; // WebView browsers, or a profile the browser has not created yet.
        try { log("browser settings checked, changed=" + BrowserPrefs.enforce(file)); }
        catch (Throwable error) { log("browser settings unavailable: " + error); }
    }

    private void browserHooks(ClassLoader loader) {
        // Chromium gets its initial ICU timezone and locale from Android. These hooks
        // also cover WebView hosts. The preference installer handles persisted languages.
        constant(XposedHelpers.findClassIfExists("org.chromium.base.LocaleUtils", loader), "getDefaultLocaleString", "en-US");
        constant(XposedHelpers.findClassIfExists("org.chromium.base.LocaleUtils", loader), "getDefaultLocaleListString", "en-US");
        constant(XposedHelpers.findClassIfExists("org.chromium.base.TimezoneUtils", loader), "getDefaultTimeZoneId", profile.zone);
    }

    private void constant(Class<?> clazz, String name, Object value) {
        hook(clazz, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Do not return an incompatible type for vendor-specific overloads.
                Class<?> type = ((Method) p.method).getReturnType();
                if (value == null) { if (!type.isPrimitive()) p.setResult(null); }
                else if (type.isInstance(value) || (type == int.class && value instanceof Integer) || (type == boolean.class && value instanceof Boolean)) p.setResult(value);
            }
        });
    }
    private void hook(Class<?> clazz, String name, XC_MethodHook callback) {
        if (clazz == null) return;
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
                    XposedBridge.hookMethod(method, callback); hookCount++;
                }
            }
        } catch (Throwable error) { log("hook failed " + clazz.getName() + "." + name + ": " + error.getClass().getSimpleName()); }
    }
    private static void log(String message) { XposedBridge.log("[RegionGuard] " + message); }
}
