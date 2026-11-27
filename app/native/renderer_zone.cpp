// Region Guard, Zygisk part: the configured time zone for Chrome and Edge renderers.
//
// Renderers are isolated processes forked from the browser's app zygote, where LSPosed does not run.
// Each one reads the phone's time zone when it starts and only later receives the zone reported by
// the hooked browser process, so a page's first scripts could see the real zone. Chrome's renderer
// reads the property natively, Edge's through Java. In the app zygote, before it forks any renderer,
// this module replaces the memory page holding persist.sys.timezone with a private copy carrying the
// configured zone, which every reader sees, and sets the Java default zone and TZ. Renderers inherit
// all of it. If the page cannot be replaced, Java property reads are hooked instead.

#include <android/log.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/time.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include "zygisk.hpp"

namespace {

constexpr const char* kTag = "RegionGuard";
constexpr const char* kTargets[] = {"com.android.chrome_zygote", "com.microsoft.emmx_zygote"};
constexpr const char* kZones[] = {
    "America/Los_Angeles", "America/New_York", "Asia/Tokyo", "Asia/Taipei", "Asia/Singapore", "Europe/London", "UTC"
};
// Written by bridge.sh while protection, browser protection and the KSU module are all on:
// "<zone> <ro.boottime.init>". A line from an earlier boot is ignored.
constexpr const char* kConfig = "/data/adb/regionguard/renderer-zone";
constexpr const char* kZoneKey = "persist.sys.timezone";
constexpr size_t kMaxZone = 63;

char zone_id[kMaxZone + 1];
const prop_info* zone_property;
jstring (*original_get)(JNIEnv*, jclass, jstring, jstring);
jstring (*original_get_handle)(JNIEnv*, jclass, jlong);

bool allowed_zone(const char* value) {
    for (const char* zone : kZones) if (strcmp(value, zone) == 0) return true;
    return false;
}

bool is_zone_key(JNIEnv* env, jstring key) {
    if (key == nullptr) return false;
    const char* text = env->GetStringUTFChars(key, nullptr);
    if (text == nullptr) { env->ExceptionClear(); return false; }
    bool match = strcmp(text, kZoneKey) == 0;
    env->ReleaseStringUTFChars(key, text);
    return match;
}

// Replacements for android.os.SystemProperties.native_get(String, String) and native_get(long).
// Java's TimeZone reads persist.sys.timezone through these whenever its default is reset.
jstring get_property(JNIEnv* env, jclass clazz, jstring key, jstring fallback) {
    if (is_zone_key(env, key)) return env->NewStringUTF(zone_id);
    return original_get(env, clazz, key, fallback);
}

jstring get_property_handle(JNIEnv* env, jclass clazz, jlong handle) {
    if (zone_property != nullptr && handle == reinterpret_cast<jlong>(zone_property)) return env->NewStringUTF(zone_id);
    return original_get_handle(env, clazz, handle);
}

void set_java_default(JNIEnv* env) {
    jclass timezone = env->FindClass("java/util/TimeZone");
    if (timezone == nullptr) { env->ExceptionClear(); return; }
    jmethodID get = env->GetStaticMethodID(timezone, "getTimeZone", "(Ljava/lang/String;)Ljava/util/TimeZone;");
    jmethodID set = env->GetStaticMethodID(timezone, "setDefault", "(Ljava/util/TimeZone;)V");
    if (get != nullptr && set != nullptr) {
        jstring id = env->NewStringUTF(zone_id);
        jobject zone = id != nullptr ? env->CallStaticObjectMethod(timezone, get, id) : nullptr;
        if (zone != nullptr && !env->ExceptionCheck()) env->CallStaticVoidMethod(timezone, set, zone);
        if (zone != nullptr) env->DeleteLocalRef(zone);
        if (id != nullptr) env->DeleteLocalRef(id);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(timezone);
}

// Bionic's prop_info: a 32-bit serial (value length in the top byte, bit 0 dirty, bit 16 long value),
// the value (PROP_VALUE_MAX bytes) and the name. __system_property_find returns its address in the
// read-only shared mapping of the property file, which here holds only persist.sys.timezone.
// Replacing the pages under it with a patched anonymous copy changes this process and its forks only.
bool patch_property_page() {
    const prop_info* info = __system_property_find(kZoneKey);
    if (info == nullptr) return false;
    const auto address = reinterpret_cast<uintptr_t>(info);
    const char* name = reinterpret_cast<const char*>(address + sizeof(uint32_t) + PROP_VALUE_MAX);
    if (strcmp(name, kZoneKey) != 0) return false;
    uint32_t serial = __atomic_load_n(reinterpret_cast<const uint32_t*>(address), __ATOMIC_ACQUIRE);
    if ((serial & 1u) != 0 || (serial & (1u << 16)) != 0) return false;  // being written, or a long value

    const auto page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    const uintptr_t first = address & ~(page - 1);
    const uintptr_t last = (address + sizeof(uint32_t) + PROP_VALUE_MAX + strlen(kZoneKey)) & ~(page - 1);
    const size_t size = last - first + page;
    void* copy = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) return false;
    memcpy(copy, reinterpret_cast<const void*>(first), size);

    char* patched = static_cast<char*>(copy) + (address - first);
    const size_t length = strlen(zone_id);
    memset(patched + sizeof(uint32_t), 0, PROP_VALUE_MAX);
    memcpy(patched + sizeof(uint32_t), zone_id, length);
    const uint32_t new_serial = (static_cast<uint32_t>(length) << 24) | (serial & 0x00fefffeu);
    memcpy(patched, &new_serial, sizeof(new_serial));

    if (mremap(copy, size, size, MREMAP_MAYMOVE | MREMAP_FIXED, reinterpret_cast<void*>(first)) == MAP_FAILED) {
        munmap(copy, size);
        return false;
    }
    mprotect(reinterpret_cast<void*>(first), size, PROT_READ);
    char check[PROP_VALUE_MAX] = "";
    return __system_property_get(kZoneKey, check) > 0 && strcmp(check, zone_id) == 0;
}

bool read_full(int fd, void* buffer, size_t size) {
    auto* bytes = static_cast<char*>(buffer);
    while (size > 0) {
        ssize_t count = read(fd, bytes, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count;
        size -= static_cast<size_t>(count);
    }
    return true;
}

bool send_full(int fd, const void* buffer, size_t size) {
    const auto* bytes = static_cast<const char*>(buffer);
    while (size > 0) {
        // MSG_NOSIGNAL: a closed peer must not kill the companion daemon with SIGPIPE.
        ssize_t count = send(fd, bytes, size, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count;
        size -= static_cast<size_t>(count);
    }
    return true;
}

class RendererZone : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        // This runs for every app start: anything that is not a target leaves immediately.
        active_ = is_target(args) && fetch_zone();
        if (!active_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    // After specialization, so nothing re-maps the property files afterwards; the app zygote is
    // still single-threaded and has not preloaded the browser yet.
    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!active_) return;
        const bool patched = patch_property_page();
        if (!patched) hook_properties();
        setenv("TZ", zone_id, 1);
        tzset();
        set_java_default(env_);
        // The patched page is plain data; only the fallback hooks need this library to stay loaded.
        if (!hooked_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        __android_log_print(ANDROID_LOG_INFO, kTag, "renderer time zone %s set in %s (%s)", zone_id, process_,
                            patched ? "property page" : hooked_ ? "Java property hooks" : "Java default only");
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool active_ = false;
    bool hooked_ = false;
    char process_[64] = "";

    bool is_target(const zygisk::AppSpecializeArgs* args) {
        if (args->is_child_zygote == nullptr || !*args->is_child_zygote || args->nice_name == nullptr) return false;
        const char* name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) { env_->ExceptionClear(); return false; }
        bool match = false;
        for (const char* target : kTargets) {
            if (strcmp(name, target) == 0) {
                match = true;
                snprintf(process_, sizeof(process_), "%s", name);
            }
        }
        env_->ReleaseStringUTFChars(args->nice_name, name);
        return match;
    }

    bool fetch_zone() {
        int socket = api_->connectCompanion();
        if (socket < 0) return false;
        // Never let a stuck companion stall the browser's app zygote.
        timeval timeout{1, 0};
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        unsigned char length = 0;
        bool ok = read_full(socket, &length, 1) && length > 0 && length <= kMaxZone && read_full(socket, zone_id, length);
        close(socket);
        if (!ok) return false;
        zone_id[length] = '\0';
        return allowed_zone(zone_id);
    }

    // Returns whether any hook is installed; the library must then stay loaded.
    bool hook_properties() {
        JNINativeMethod methods[] = {
            {"native_get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(get_property)},
            {"native_get", "(J)Ljava/lang/String;", reinterpret_cast<void*>(get_property_handle)},
        };
        api_->hookJniNativeMethods(env_, "android/os/SystemProperties", methods, 2);
        original_get = reinterpret_cast<decltype(original_get)>(methods[0].fnPtr);
        original_get_handle = reinterpret_cast<decltype(original_get_handle)>(methods[1].fnPtr);
        zone_property = __system_property_find(kZoneKey);
        hooked_ = original_get != nullptr || original_get_handle != nullptr;
        return hooked_;
    }
};

// Root companion: reads the zone that bridge.sh published for this boot.
void serve_zone(int client) {
    char line[160] = "";
    if (FILE* file = fopen(kConfig, "re")) {
        if (fgets(line, sizeof(line), file) == nullptr) line[0] = '\0';
        fclose(file);
    }
    char boot[PROP_VALUE_MAX] = "";
    __system_property_get("ro.boottime.init", boot);
    unsigned char length = 0;
    if (char* separator = strchr(line, ' '); separator != nullptr && boot[0] != '\0') {
        *separator = '\0';
        char* saved_boot = separator + 1;
        saved_boot[strcspn(saved_boot, "\r\n")] = '\0';
        if (strcmp(saved_boot, boot) == 0 && allowed_zone(line)) length = static_cast<unsigned char>(strlen(line));
    }
    if (send_full(client, &length, 1) && length > 0) send_full(client, line, length);
}

}  // namespace

REGISTER_ZYGISK_MODULE(RendererZone)
REGISTER_ZYGISK_COMPANION(serve_zone)
