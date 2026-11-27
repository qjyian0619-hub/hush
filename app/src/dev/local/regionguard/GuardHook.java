package dev.local.regionguard;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.error.XposedFrameworkError;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class GuardHook extends XposedModule {
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList("com.android.chrome", "com.microsoft.emmx", "mark.via", "com.quark.browser"));
    private String processName;
    private boolean installed;
    private Profile profile;
    private int hookCount;

    @Override public void onModuleLoaded(ModuleLoadedParam param) { processName = param.getProcessName(); }

    @Override public synchronized void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        ApplicationInfo appInfo = param.getApplicationInfo();
        // LSPosed scope is the explicit allowlist. Never hook critical system processes.
        if (installed || packageName.equals(Profile.PACKAGE) || appInfo == null || appInfo.uid < 10000
                || packageName.equals("com.android.systemui") || packageName.equals("com.android.settings")) return;
        try {
            profile = new Profile(getRemotePreferences(Profile.PREFS));
        } catch (RuntimeException | XposedFrameworkError error) {
            if (param.isFirstPackage()) log("inactive package=" + packageName + ": remote preferences unavailable: " + error);
            return;
        }
        if (profile.enabled && !profile.managerEnabled && param.isFirstPackage()) {
            log("inactive package=" + packageName + ": KernelSU module state not synced for this boot (marker "
                + (Profile.bootMarker().isEmpty() ? "unreadable" : "mismatch") + ")");
        }
        if (!profile.enabled || !profile.managerEnabled || (BROWSERS.contains(packageName) && !profile.browsers)) return;
        installed = true;
        // Only the main browser process writes the profile, and it has not read it yet at this point.
        if (BROWSERS.contains(packageName) && packageName.equals(processName)) browserSettings(appInfo.dataDir);
        System.setProperty("regionguard.active", Profile.VERSION);
        System.setProperty("regionguard.zone", profile.zone);
        try {
            try { System.loadLibrary("regionguard"); }
            catch (UnsatisfiedLinkError first) { System.load(getModuleApplicationInfo().sourceDir + "!/lib/arm64-v8a/libregionguard.so"); }
            NativeBridge.configure(profile.zone, profile.language, profile.locale.getLanguage(), profile.locale.getCountry());
            System.setProperty("regionguard.native", NativeBridge.probe());
        } catch (Throwable error) { log("native unavailable: " + error); }
        localeHooks();
        telephonyHooks();
        locationHooks();
        wifiHooks();
        propertyHooks();
        browserHooks(param.getClassLoader());
        hookNamed(Application.class, "attach", chain -> {
            try {
                return chain.proceed();
            } finally {
                try {
                    Context context = (Context) chain.getArg(0);
                    Configuration configuration = context.getResources().getConfiguration();
                    configuration.setLocales(new LocaleList(profile.locale));
                    configuration.mcc = 0; configuration.mnc = 0;
                    context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
                } catch (Throwable ignored) {}
                log("active package=" + packageName + " process=" + processName + " hooks=" + hookCount
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
        System.setProperty("user.language", profile.locale.getLanguage());
        System.setProperty("user.country", profile.locale.getCountry());
        try { Os.setenv("TZ", profile.zone, true); } catch (Throwable ignored) {}
        for (String name : new String[]{"getDefault", "getDefaultRef"}) {
            hookCount += hookNamed(TimeZone.class, name, chain -> timezone.clone());
        }
        constant(Locale.class, "getDefault", profile.locale);
        constant(LocaleList.class, "getDefault", new LocaleList(profile.locale));
        constant(LocaleList.class, "getAdjustedDefault", new LocaleList(profile.locale));
        constant(Configuration.class, "getLocales", new LocaleList(profile.locale));
        constant(android.icu.util.ULocale.class, "getDefault", android.icu.util.ULocale.forLocale(profile.locale));
        android.icu.util.TimeZone icuZone = android.icu.util.TimeZone.getTimeZone(profile.zone);
        try {
            Method setDefault = android.icu.util.TimeZone.class.getDeclaredMethod("setDefault", android.icu.util.TimeZone.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, icuZone);
        } catch (Throwable ignored) {}
        hookCount += hookNamed(android.icu.util.TimeZone.class, "getDefault", chain -> icuZone.clone());
        hookCount += hookNamed(Resources.class, "getConfiguration", chain -> {
            Object result = chain.proceed();
            if (!(result instanceof Configuration)) return result;
            Configuration copy = new Configuration((Configuration) result);
            copy.setLocales(new LocaleList(profile.locale)); copy.mcc = 0; copy.mnc = 0;
            return copy;
        });
        Class<?> localeManager = findClass("android.app.LocaleManager", null);
        constant(localeManager, "getSystemLocales", new LocaleList(profile.locale));
        constant(localeManager, "getApplicationLocales", new LocaleList(profile.locale));
        Class<?> timezoneGetter = findClass("libcore.util.TimezoneGetter", null);
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
            hookCount += hookNamed(LocationManager.class, name, chain -> {
                throw new SecurityException("Location access disabled by RegionGuard");
            }, ExceptionMode.PASSTHROUGH);
        }
        constant(findClass("android.location.Geocoder", null), "isPresent", false);
    }

    private void wifiHooks() {
        constant(WifiInfo.class, "getSSID", "<unknown ssid>");
        constant(WifiInfo.class, "getBSSID", "02:00:00:00:00:00");
        constant(WifiInfo.class, "getMacAddress", "02:00:00:00:00:00");
        constant(WifiManager.class, "getScanResults", Collections.emptyList());
        constant(WifiManager.class, "getConfiguredNetworks", Collections.emptyList());
        constant(WifiManager.class, "getPrivilegedConfiguredNetworks", Collections.emptyList());
        constant(WifiManager.class, "startScan", false);
        constant(findClass("android.net.NetworkInfo", null), "getExtraInfo", "");
    }

    private String property(String key) {
        if (key.equals("persist.sys.timezone")) return profile.zone;
        if (key.equals("persist.sys.locale") || key.equals("ro.product.locale")) return profile.language;
        if (key.equals("persist.sys.language") || key.equals("ro.product.locale.language")) return profile.locale.getLanguage();
        if (key.equals("persist.sys.country") || key.equals("ro.product.locale.region")) return profile.locale.getCountry();
        if (key.startsWith("gsm.sim.operator.") || key.startsWith("gsm.operator.") || key.startsWith("vendor.gsm.sim.operator.") || key.startsWith("vendor.gsm.operator.")) return "";
        if (key.equals("gsm.sim.state")) return "ABSENT,ABSENT";
        if (key.equals("ro.oplus.regionmark") || key.equals("ro.vendor.oplus.regionmark") || key.equals("persist.sys.oplus.region")
                || key.equals("persist.sys.oplus.regionmark") || key.equals("ro.miui.region") || key.equals("ro.csc.countryiso_code")) return "";
        return null;
    }

    private void propertyHooks() {
        Hooker localeSetting = chain -> {
            for (Object argument : chain.getArgs()) if ("system_locales".equals(argument)) return profile.language;
            return chain.proceed();
        };
        hookCount += hookNamed(android.provider.Settings.System.class, "getString", localeSetting);
        hookCount += hookNamed(android.provider.Settings.System.class, "getStringForUser", localeSetting);
        hookCount += hookNamed(findClass("android.os.SystemProperties", null), "get", chain -> {
            if (!chain.getArgs().isEmpty() && chain.getArg(0) instanceof String) {
                String replacement = property((String) chain.getArg(0));
                if (replacement != null) return replacement;
            }
            return chain.proceed();
        });
    }

    /** Chromium browsers (Chrome, Edge): page languages, WebRTC IP handling, geolocation and sensors. */
    private void browserSettings(String dataDir) {
        java.io.File file = new java.io.File(dataDir, BrowserPrefs.PROFILE);
        if (!file.isFile()) return; // WebView browsers, or a profile the browser has not created yet.
        try { log("browser settings checked, changed=" + BrowserPrefs.enforce(file, profile.locale)); }
        catch (Throwable error) { log("browser settings unavailable: " + error); }
    }

    private void browserHooks(ClassLoader loader) {
        // Chromium gets its initial ICU timezone and locale from Android. These hooks
        // also cover WebView hosts. The preference installer handles persisted languages.
        Class<?> localeUtils = findClass("org.chromium.base.LocaleUtils", loader);
        constant(localeUtils, "getDefaultLocaleString", profile.language);
        constant(localeUtils, "getDefaultLocaleListString", profile.language);
        constant(findClass("org.chromium.base.TimezoneUtils", loader), "getDefaultTimeZoneId", profile.zone);
    }

    private void constant(Class<?> clazz, String name, Object value) {
        hookCount += hookNamed(clazz, name, chain -> {
            // Do not return an incompatible type for vendor-specific overloads.
            Class<?> type = ((Method) chain.getExecutable()).getReturnType();
            if (value == null && !type.isPrimitive()) return null;
            if (type.isInstance(value) || (type == int.class && value instanceof Integer)
                    || (type == boolean.class && value instanceof Boolean)) return value;
            return chain.proceed();
        });
    }
    private int hookNamed(Class<?> clazz, String name, Hooker hooker) {
        return hookNamed(clazz, name, hooker, ExceptionMode.DEFAULT);
    }
    private int hookNamed(Class<?> clazz, String name, Hooker hooker, ExceptionMode mode) {
        if (clazz == null) return 0;
        int count = 0;
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
                    hook(method).setExceptionMode(mode).intercept(hooker);
                    count++;
                }
            }
        } catch (Throwable error) { log("hook failed " + clazz.getName() + "." + name + ": " + error.getClass().getSimpleName()); }
        return count;
    }
    private static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError ignored) { return null; }
    }
    private void log(String message) { log(Log.INFO, "RegionGuard", "[RegionGuard] " + message); }
}
