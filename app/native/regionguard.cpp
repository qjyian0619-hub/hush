#include <jni.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <time.h>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <atomic>

using HookFunction = int (*)(void*, void*, void**);
using LibraryCallback = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; HookFunction hook_func; int (*unhook_func)(void*); };
struct PropertyProfile {
    char timezone[PROP_VALUE_MAX];
    char locale[PROP_VALUE_MAX];
    char language[PROP_VALUE_MAX];
    char country[PROP_VALUE_MAX];
};
static const PropertyProfile default_profile{"America/Los_Angeles", "en-US", "en", "US"};
static PropertyProfile configured_profile;
static std::atomic_flag configuring = ATOMIC_FLAG_INIT;
static std::atomic<const PropertyProfile*> active_profile{&default_profile};
static int status_bits = 0;
static int (*original_get)(const char*, char*) = nullptr;
static int (*original_read)(const prop_info*, char*, char*) = nullptr;
using PropertyCallback = void (*)(void*, const char*, const char*, uint32_t);
static void (*original_read_callback)(const prop_info*, PropertyCallback, void*) = nullptr;

static const char* replacement(const char* key) {
    if (!key) return nullptr;
    const PropertyProfile* profile = active_profile.load(std::memory_order_acquire);
    if (!strcmp(key,"persist.sys.timezone")) return profile->timezone;
    if (!strcmp(key,"persist.sys.locale") || !strcmp(key,"ro.product.locale")) return profile->locale;
    if (!strcmp(key,"persist.sys.language") || !strcmp(key,"ro.product.locale.language")) return profile->language;
    if (!strcmp(key,"persist.sys.country") || !strcmp(key,"ro.product.locale.region")) return profile->country;
    if (!strncmp(key,"gsm.sim.operator.",17) || !strncmp(key,"gsm.operator.",13)
        || !strncmp(key,"vendor.gsm.sim.operator.",24) || !strncmp(key,"vendor.gsm.operator.",20)) return "";
    if (!strcmp(key,"gsm.sim.state")) return "ABSENT,ABSENT";
    if (!strcmp(key,"ro.oplus.regionmark") || !strcmp(key,"ro.vendor.oplus.regionmark")
        || !strcmp(key,"persist.sys.oplus.region") || !strcmp(key,"persist.sys.oplus.regionmark")
        || !strcmp(key,"ro.miui.region") || !strcmp(key,"ro.csc.countryiso_code")) return "";
    return nullptr;
}

static int guarded_get(const char* key, char* value) {
    const char* changed = replacement(key);
    if (changed) { size_t size = strlen(changed); memcpy(value, changed, size+1); return static_cast<int>(size); }
    return original_get(key, value);
}
static int guarded_read(const prop_info* property, char* name, char* value) {
    char temporary_name[128]{};
    char* resolved_name = name ? name : temporary_name;
    int result = original_read(property, resolved_name, value);
    const char* changed = replacement(resolved_name);
    if (changed) {size_t size = strlen(changed); memcpy(value,changed,size+1); return static_cast<int>(size);}
    return result;
}
struct CallbackContext { PropertyCallback callback; void* cookie; };
static void guarded_callback(void* cookie, const char* key, const char* value, uint32_t serial) {
    auto* context = static_cast<CallbackContext*>(cookie);
    const char* changed = replacement(key);
    context->callback(context->cookie, key, changed ? changed : value, serial);
}
static void guarded_read_callback(const prop_info* property, PropertyCallback callback, void* cookie) {
    CallbackContext context{callback, cookie};
    original_read_callback(property, guarded_callback, &context);
}
extern "C" __attribute__((visibility("default"),used)) LibraryCallback native_init(const NativeAPIEntries* entries) {
    status_bits = 1;
    void* libc = dlopen("libc.so", RTLD_NOW | RTLD_LOCAL);
    if (libc) {
        void* get = dlsym(libc,"__system_property_get");
        void* read = dlsym(libc,"__system_property_read_callback");
        void* old_read = dlsym(libc,"__system_property_read");
        if (get && entries->hook_func(get,reinterpret_cast<void*>(guarded_get),reinterpret_cast<void**>(&original_get)) == 0) status_bits |= 2;
        if (read && entries->hook_func(read,reinterpret_cast<void*>(guarded_read_callback),reinterpret_cast<void**>(&original_read_callback)) == 0) status_bits |= 4;
        if (old_read && entries->hook_func(old_read,reinterpret_cast<void*>(guarded_read),reinterpret_cast<void**>(&original_read)) == 0) status_bits |= 8;
        dlclose(libc);
    }
    return nullptr;
}
static bool copy_property(JNIEnv* env, jstring value, char (&output)[PROP_VALUE_MAX]) {
    if (!value) return false;
    jsize length = env->GetStringUTFLength(value);
    if (length >= PROP_VALUE_MAX) return false;
    env->GetStringUTFRegion(value, 0, env->GetStringLength(value), output);
    output[length] = '\0';
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT void JNICALL Java_dev_local_regionguard_NativeBridge_configure(
        JNIEnv* env, jclass, jstring timezone, jstring locale, jstring language, jstring country) {
    PropertyProfile values{};
    if (!copy_property(env, timezone, values.timezone) || !copy_property(env, locale, values.locale)
            || !copy_property(env, language, values.language) || !copy_property(env, country, values.country)) {
        if (!env->ExceptionCheck()) {
            jclass error = env->FindClass("java/lang/IllegalArgumentException");
            if (error) env->ThrowNew(error, "Invalid locale or timezone property");
        }
        return;
    }
    // Each app process keeps the profile it loaded at startup.
    if (configuring.test_and_set(std::memory_order_relaxed)) return;
    configured_profile = values;
    active_profile.store(&configured_profile, std::memory_order_release);
    setenv("TZ", configured_profile.timezone, 1);
    tzset();
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_local_regionguard_NativeBridge_probe(JNIEnv* env,jclass) {
    char country[PROP_VALUE_MAX]{}, timezone[PROP_VALUE_MAX]{}, locale[PROP_VALUE_MAX]{}, buffer[512]{};
    __system_property_get("gsm.sim.operator.iso-country",country);
    __system_property_get("persist.sys.timezone",timezone);
    __system_property_get("persist.sys.locale",locale);
    snprintf(buffer, sizeof(buffer), "{\"hookStatus\":%d,\"simCountry\":\"%s\",\"timezone\":\"%s\",\"locale\":\"%s\"}",
             status_bits, country, timezone, locale);
    return env->NewStringUTF(buffer);
}
