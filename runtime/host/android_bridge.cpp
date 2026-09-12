// The Android bridge. See android_bridge.h for the rule about what belongs here; this file
// is that rule's whole implementation, plus the one thing every Android native entry point
// has to solve before anything else can be diagnosed: where does stderr go.
//
// WHERE DOES THE OUTPUT GO. This runtime's entire diagnostic surface is fprintf(stderr, ...) —
// the boot banner, every env-var arm it decides it is in, the renderer's counter dumps, the
// crash report — and host/log_file.cpp tees it to a file beside the game folder. On Android
// a non-debuggable app's stdout AND stderr are /dev/null, so without this file the tee would
// be writing
// a file whose console copy nobody can see and `adb logcat` would show nothing from a
// 1.2 GB game boot. The redirect below is installed BEFORE CwRuntimeMain, i.e. before
// LogFile::Begin dup2's stderr into its own pipe and keeps a dup of the original to echo to:
// the tee's "console copy" therefore goes to logcat, and log_file.cpp did not need to learn
// anything about Android. One pipe, one reader thread, no change to the module that owns
// the logging policy.
#if defined(__ANDROID__)

#include "android_bridge.h"

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "android_perf.h"
#include "cw_main.h"
#include "../cpu/touch_input.h"
#include "../gpu/vk_shadow_android.h"

#define CW_TAG "CaseWest"
#define CW_LOGI(...) __android_log_print(ANDROID_LOG_INFO, CW_TAG, __VA_ARGS__)
#define CW_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CW_TAG, __VA_ARGS__)

namespace
{

JavaVM* g_jvm = nullptr;

// The Activity we may call back into. A global reference and a method id, both guarded:
// the guest's rumble path runs on the window thread while the UI thread can be tearing the
// Activity down, and a stale jobject is not a null check — it is a use of freed memory
// inside the JVM. Cleared from nativeClearActivity, which the Activity calls in onDestroy.
std::mutex g_activityMutex;
jobject g_activity = nullptr;
jmethodID g_rumbleMethod = nullptr;
// The first-run progress callbacks. Null when the Activity does not implement them, which
// is a launcher with no progress UI rather than an error: ProgressBegin then returns false
// and the work still runs, with the console lines as its only output.
jmethodID g_progressBeginMethod = nullptr;
jmethodID g_progressMethod = nullptr;
jmethodID g_progressEndMethod = nullptr;

// ---------------------------------------------------------------------------------------
// stdout and stderr -> logcat
// ---------------------------------------------------------------------------------------
std::atomic<bool> g_logcatLive{ false };

void LogcatReader(int fd)
{
    // Line-buffered rather than chunk-buffered: logcat's own limit is about 4 KB a message
    // and a 64 KB read of a boot log would be truncated into nothing, while a line is what
    // every reader of this log expects to see. Splitting on '\n' also keeps the tee's
    // partial-line behaviour out of logcat's record boundaries.
    std::string line;
    char buf[4096];
    for (;;)
    {
        const ssize_t n = ::read(fd, buf, sizeof buf);
        if (n <= 0)
        {
            if (n < 0 && errno == EINTR)
                continue;
            break;   // EOF: the write end closed, which only happens at exit
        }
        for (ssize_t i = 0; i < n; ++i)
        {
            const char c = buf[i];
            if (c == '\n')
            {
                if (!line.empty())
                    __android_log_write(ANDROID_LOG_INFO, CW_TAG, line.c_str());
                line.clear();
                continue;
            }
            if (c == '\r')
                continue;
            line.push_back(c);
            // A line with no newline in 4 KB is a progress bar or a runaway; cut it rather
            // than grow without bound inside a thread nobody can interrupt.
            if (line.size() >= 3800)
            {
                __android_log_write(ANDROID_LOG_INFO, CW_TAG, line.c_str());
                line.clear();
            }
        }
    }
    if (!line.empty())
        __android_log_write(ANDROID_LOG_INFO, CW_TAG, line.c_str());
    ::close(fd);
    g_logcatLive.store(false, std::memory_order_release);
}

void RedirectStdioToLogcat()
{
    const char* off = getenv("CW_ANDROID_LOGCAT");
    if (off && *off && *off != '0')
    {
        CW_LOGI("CW_ANDROID_LOGCAT=%s — stdout and stderr are NOT mirrored to logcat; the "
                "file tee in cw_runtime.log is the only copy.", off);
        return;
    }

    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0)
    {
        CW_LOGE("pipe2 for the logcat mirror failed (%s) — continuing without it; "
                "cw_runtime.log still has everything.", strerror(errno));
        return;
    }
    // The write end becomes stderr AND stdout. dup2 clears O_CLOEXEC on the destination,
    // which is what we want: every thread in this process writes to fd 2 and fd 1 and all of
    // it must reach the mirror.
    //
    // STDOUT IS INCLUDED, and the reason is that this runtime's output is split across the two
    // streams by a rule nobody chose deliberately: the diagnostics are fprintf(stderr, ...) and
    // the two harnesses are printf. `--smoke`'s verdict — "OK: every generated symbol resolved
    // and every mapping entry is sane." — is a printf, so on a platform where stdout is
    // /dev/null the gate that proves the artifact BOOTS would print its answer to nowhere. That
    // is not a hypothetical: it is what .github/workflows/android.yml's emulator job has to
    // read out of logcat to decide whether an APK runs.
    //
    // Only stderr is tee'd to cw_runtime.log (host/log_file.cpp dup2's fd 2 and keeps a dup of
    // the original to echo to), so stdout reaches logcat and not the file. That is the same
    // split the desktop has always had — the log file never contained the harness output — and
    // changing it here would mean changing the logging policy in a platform file, which is the
    // opposite of the reason this redirect exists.
    if (dup2(fds[1], STDERR_FILENO) < 0)
    {
        CW_LOGE("dup2 to stderr failed (%s) — the logcat mirror is not installed.",
                strerror(errno));
        ::close(fds[0]);
        ::close(fds[1]);
        return;
    }
    if (dup2(fds[1], STDOUT_FILENO) < 0)
    {
        // stderr is already mirrored at this point, so this is a partial success rather than a
        // failure to undo: the diagnostics still arrive and the harness verdicts do not. Say
        // which happened, because the two look identical from a log with no printf output in it.
        CW_LOGE("dup2 to stdout failed (%s) — stderr is mirrored but printf output is NOT. "
                "`--smoke`'s verdict will be missing from logcat.", strerror(errno));
    }
    ::close(fds[1]);   // the process holds fds 1 and 2 now; keeping this copy would keep the
                       // pipe open after a hypothetical restore and the reader would never see
                       // EOF

    g_logcatLive.store(true, std::memory_order_release);
    std::thread(LogcatReader, fds[0]).detach();
    CW_LOGI("stdout and stderr mirrored to logcat (tag %s). CW_ANDROID_LOGCAT=0 turns this "
            "off; the file tee under the game folder is independent of it.", CW_TAG);
}

// ---------------------------------------------------------------------------------------
// The env contract, checked rather than assumed.
// ---------------------------------------------------------------------------------------
// CW_ROOT is the one variable without which nothing works: host_paths.cpp resolves every
// asset, cache and save path from it, and on Android there is no executable directory to
// fall back to (its QueryExePath returns our own .so, in a read-only directory). The Java
// side sets it with Os.setenv before System.loadLibrary, and this is the check that turns
// "the launcher forgot" into one line naming the variable instead of a first-run gate
// reporting a missing package in a tree that was never writable.
void CheckEnvironment()
{
    const char* root = getenv("CW_ROOT");
    if (!root || !*root)
    {
        CW_LOGE("CW_ROOT is not set. The launcher must export it (the app's private "
                "<filesDir>/cw) before the runtime starts; nothing below will find the "
                "game, the shader cache or the saves.");
        fprintf(stderr, "[android] CW_ROOT IS NOT SET — refusing to boot. See "
                        "android/app/src/main/java/dev/casewest/android/GameActivity.kt, "
                        "which is what normally sets it.\n");
        return;
    }
    fprintf(stderr, "[android] CW_ROOT=%s\n", root);

    // The variables the launcher is expected to have set, printed whether set or not — the
    // same discipline as main.cpp's own env dump, because an env var a bug report does not
    // mention is an env var nobody can tell was unset or unlogged.
    static const char* kInteresting[] = {
        "CW_VK_DRIVER_DIR", "CW_VK_DRIVER_NAME", "CW_VK_NO_CUSTOM_DRIVER", "CW_VK_TURBO",
        "CW_NO_TOUCH", "CW_VK_RES", "CW_VK_MSAA", "CW_FPS_CAP", "CW_SHADOW_TIER",
        "CW_ANDROID_AUTO_SCALE", "CW_ANDROID_THERMAL", "CW_ANDROID_PIN_BIG",
        "CW_ANDROID_TARGET_FPS", "CW_DXC_LIB", "CW_LAUNCHER", "CW_VKDRAW",
    };
    for (const char* k : kInteresting)
    {
        const char* v = getenv(k);
        fprintf(stderr, "[android] env %-26s %s\n", k, v ? v : "(unset)");
    }
}

}  // namespace

// =======================================================================================
// THE ENTRY POINT.
//
// SDLActivity.nativeRunMain dlopens our library with RTLD_GLOBAL, dlsyms "SDL_main" out of
// it and calls it on SDL's own thread with argv[0] = "app_process" plus whatever
// getArguments() returned. Everything the desktop build does in main(), this does, in the
// same order — see host/cw_main.h for why the body is a function and not a platform-specific
// copy. The two Android-only steps before it are the ones that have to happen before any
// other code runs: the log mirror (so the boot is visible) and the Vulkan loader (so the
// first vkCreateInstance is already going through adrenotools and the driver the player
// chose).
// =======================================================================================
extern "C" int SDL_main(int argc, char* argv[])
{
    RedirectStdioToLogcat();
    CheckEnvironment();

    // Phase 1 of the loader fill, early and explicit. gpu/vk_shadow_android.cpp's exported
    // symbols would do this lazily on first use, and that is the fallback; doing it here
    // means the "[vk-loader]" lines are at the TOP of the boot log, above the renderer's
    // own, which is where someone reading a phone's log for "which driver was it" looks.
    if (!CwVk::InitLoader())
    {
        CW_LOGE("no Vulkan loader: this device cannot run the port (64-bit with a Vulkan "
                "driver required).");
        fprintf(stderr, "[android] NO VULKAN LOADER. The runtime will now fail at device "
                        "creation; the [vk-loader] lines above say what was tried.\n");
    }

    const int rc = CwRuntimeMain(argc, argv);

    // The two reports that only make sense at the end of a session, printed whether or not
    // anything happened in them — a phone that never received a touch snapshot and one with
    // touch disabled must be distinguishable in the log (gotcha 5's other half).
    TouchInput_ReportStats();
    AndroidPerf::Report();

    fprintf(stderr, "[android] runtime returned %d\n", rc);
    CW_LOGI("runtime returned %d", rc);
    return rc;
}

// =======================================================================================
// Java -> native
// =======================================================================================
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/)
{
    std::lock_guard<std::mutex> lock(g_activityMutex);
    g_activity = nullptr;
    g_rumbleMethod = nullptr;
    g_progressBeginMethod = g_progressMethod = g_progressEndMethod = nullptr;
}

// The Activity that will receive native callbacks. Called from GameActivity.onCreate AFTER
// super.onCreate (i.e. after SDL has loaded this library), and released from onDestroy.
JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeSetActivity(JNIEnv* env, jobject /*thiz*/,
                                                         jobject activity)
{
    std::lock_guard<std::mutex> lock(g_activityMutex);
    if (g_activity)
    {
        env->DeleteGlobalRef(g_activity);
        g_activity = nullptr;
        g_rumbleMethod = nullptr;
        g_progressBeginMethod = g_progressMethod = g_progressEndMethod = nullptr;
    }
    if (!activity)
        return;

    jclass cls = env->GetObjectClass(activity);
    // All three callbacks are looked up together and registered together: a partial
    // registration is the kind of state that makes "the progress bar never appeared" and
    // "vibration does not work" two different bugs with one cause.
    if (cls)
    {
        g_progressBeginMethod = env->GetMethodID(cls, "onNativeProgressBegin", "(Ljava/lang/String;)Z");
        g_progressMethod = env->GetMethodID(cls, "onNativeProgress", "(Ljava/lang/String;F)V");
        g_progressEndMethod = env->GetMethodID(cls, "onNativeProgressEnd", "()V");
        if (env->ExceptionCheck())
            env->ExceptionClear();
    }
    jmethodID mid = cls ? env->GetMethodID(cls, "onNativeRumble", "(II)V") : nullptr;
    if (!mid)
    {
        // Not fatal — vibration is the one feature whose absence changes nothing about the
        // game — but it must be said, because "my phone does not rumble" is otherwise a
        // report about a title that has no way to be wrong.
        if (env->ExceptionCheck())
            env->ExceptionClear();
        CW_LOGE("onNativeRumble(II)V not found on the Activity: controller vibration will "
                "not reach the phone's vibrator.");
        env->DeleteLocalRef(cls);
        return;
    }
    env->DeleteLocalRef(cls);

    g_activity = env->NewGlobalRef(activity);
    g_rumbleMethod = mid;
}

JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeClearActivity(JNIEnv* env, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_activityMutex);
    if (g_activity)
        env->DeleteGlobalRef(g_activity);
    g_activity = nullptr;
    g_rumbleMethod = nullptr;
    g_progressBeginMethod = g_progressMethod = g_progressEndMethod = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_dev_casewest_android_NativeBridge_nativeInitVulkan(JNIEnv* /*env*/, jobject /*thiz*/)
{
    return CwVk::InitLoader() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_casewest_android_NativeBridge_nativeVulkanLoaderSource(JNIEnv* env, jobject /*thiz*/)
{
    return env->NewStringUTF(CwVk::LoaderSource());
}

JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeTouchPublish(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jint buttons, jint leftTrigger,
                                                          jint rightTrigger, jint thumbLX,
                                                          jint thumbLY, jint thumbRX,
                                                          jint thumbRY, jint fingers)
{
    TouchSnapshot s;
    // Truncation rather than masking on the button field: TouchInput_Publish masks to the
    // bits this runtime owns, and a jshort cast here would silently drop bit 16 and above
    // in a way that looks like the overlay not pressing anything.
    s.buttons = uint16_t(buttons);
    s.leftTrigger = uint8_t(leftTrigger < 0 ? 0 : leftTrigger > 255 ? 255 : leftTrigger);
    s.rightTrigger = uint8_t(rightTrigger < 0 ? 0 : rightTrigger > 255 ? 255 : rightTrigger);
    s.thumbLX = int16_t(thumbLX);
    s.thumbLY = int16_t(thumbLY);
    s.thumbRX = int16_t(thumbRX);
    s.thumbRY = int16_t(thumbRY);
    s.activeFingers = uint16_t(fingers < 0 ? 0 : fingers > 65535 ? 65535 : fingers);
    TouchInput_Publish(s);
}

JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeTouchClear(JNIEnv* /*env*/, jobject /*thiz*/)
{
    TouchInput_Clear();
}

JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeTouchSetEnabled(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jboolean enabled)
{
    TouchInput_SetEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_casewest_android_NativeBridge_nativeSetThermalStatus(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jint status)
{
    AndroidPerf::OnThermalStatus(int(status));
}

// One block of text for the launcher's diagnostics screen and for a bug report: the driver
// actually in use, the governor's position, and the touch counters. Assembled natively
// because every number in it is native state, and a screen that shows numbers the Java side
// had to guess at is worse than no screen.
JNIEXPORT jstring JNICALL
Java_dev_casewest_android_NativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject /*thiz*/)
{
    const AndroidPerf::State p = AndroidPerf::CurrentState();
    const TouchStats& t = TouchInput_Stats();

    char buf[1024];
    snprintf(buf, sizeof buf,
             "vulkan loader   %s\n"
             "touch controls  %s\n"
             "  publishes     %llu   merged %llu   presses %llu   contradictions %llu\n"
             "perf governor   frame-rate %s, thermal %s (status %d)\n"
             "  ladder        %d rung(s), %ux%u .. %ux%u\n"
             "  now           rung %d, %ux%u (target %.0f fps, last %.1f fps)\n"
             "  moves         %llu down, %llu up\n",
             CwVk::LoaderSource(), TouchInput_Enabled() ? "ON" : "OFF",
             (unsigned long long)t.publishes, (unsigned long long)t.merges,
             (unsigned long long)t.buttonPresses, (unsigned long long)t.rejectedLeaks,
             p.enabled ? "ON" : "off", p.thermalEnabled ? "ON" : "off", p.thermalStatus,
             p.steps, p.ceilingW, p.ceilingH, p.floorW, p.floorH, p.step + 1, p.currentW,
             p.currentH, p.targetFps, p.lastFps, (unsigned long long)p.downMoves,
             (unsigned long long)p.upMoves);
    return env->NewStringUTF(buf);
}

}  // extern "C"

// =======================================================================================
// native -> Java
// =======================================================================================
namespace AndroidBridge
{

bool Ready()
{
    std::lock_guard<std::mutex> lock(g_activityMutex);
    return g_jvm && g_activity && g_rumbleMethod;
}

void Rumble(uint16_t leftMotor, uint16_t rightMotor)
{
    jobject activity = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_activityMutex);
        if (!g_jvm || !g_activity || !g_rumbleMethod)
            return;
        activity = g_activity;
        method = g_rumbleMethod;
    }

    // The window thread is not a Java thread and the JVM has never seen it, so it has to be
    // attached before it can call anything — and DETACHED again, because a thread left
    // attached keeps its JNIEnv and its thread-local storage alive for the JVM's lifetime
    // and cannot exit cleanly. Attach/detach per call is a few microseconds against a
    // rumble request that arrives at most every 250 ms (window.cpp's refresh cadence), and
    // the alternative — a thread-local cached env with a destructor that detaches — is a
    // second mechanism for one call site.
    JNIEnv* env = nullptr;
    bool attachedHere = false;
    jint st = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED)
    {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return;
        attachedHere = true;
    }
    if (!env)
        return;

    env->CallVoidMethod(activity, method, jint(leftMotor), jint(rightMotor));
    if (env->ExceptionCheck())
    {
        // Print and clear: an exception left pending on a thread that returns to native
        // code is undefined behaviour at the next JNI call, and the next JNI call here is
        // the next rumble request, 250 ms later, in a game that is otherwise running fine.
        __android_log_print(ANDROID_LOG_ERROR, CW_TAG,
                            "onNativeRumble threw; clearing so the next call is defined.");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attachedHere)
        g_jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------------------
// The first-run progress. Same attach/detach discipline as Rumble and for the same reason:
// these are called from the runtime's MAIN thread (the one SDL_main runs on), which the JVM
// has never seen.
// ---------------------------------------------------------------------------------------
namespace
{

// One JNI call, with the attach and the exception clearing that every one of them needs.
// Returns false when there is nobody to call — no Activity registered, or an Activity whose
// class does not implement this callback — which lets ProgressBegin answer "no listener"
// honestly instead of guessing from a null method id.
template <typename Call>
bool CallActivity(jmethodID method, Call&& call)
{
    jobject activity = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_activityMutex);
        if (!g_jvm || !g_activity || !method)
            return false;
        activity = g_activity;
    }

    JNIEnv* env = nullptr;
    bool attachedHere = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
    {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return false;
        attachedHere = true;
    }
    if (!env)
        return false;

    call(env, activity, method);
    if (env->ExceptionCheck())
    {
        __android_log_print(ANDROID_LOG_ERROR, CW_TAG,
                            "a progress callback threw; clearing so the next JNI call on "
                            "this thread is defined.");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attachedHere)
        g_jvm->DetachCurrentThread();
    return true;
}

}  // namespace

bool AndroidBridge::ProgressBegin(const char* title)
{
    jmethodID method;
    {
        std::lock_guard<std::mutex> lock(g_activityMutex);
        method = g_progressBeginMethod;
    }
    const std::string t = title ? title : "";
    bool answered = false;
    const bool called = CallActivity(method,
        [&](JNIEnv* env, jobject activity, jmethodID m) {
            jstring js = env->NewStringUTF(t.c_str());
            answered = env->CallBooleanMethod(activity, m, js) == JNI_TRUE;
            env->DeleteLocalRef(js);
        });
    return called && answered;
}

void AndroidBridge::ProgressUpdate(const char* line, float fraction)
{
    jmethodID method;
    {
        std::lock_guard<std::mutex> lock(g_activityMutex);
        method = g_progressMethod;
    }
    const std::string l = line ? line : "";
    CallActivity(method, [&](JNIEnv* env, jobject activity, jmethodID m) {
        jstring js = env->NewStringUTF(l.c_str());
        env->CallVoidMethod(activity, m, js, jfloat(fraction));
        env->DeleteLocalRef(js);
    });
}

void AndroidBridge::ProgressEnd()
{
    jmethodID method;
    {
        std::lock_guard<std::mutex> lock(g_activityMutex);
        method = g_progressEndMethod;
    }
    CallActivity(method, [&](JNIEnv* env, jobject activity, jmethodID m) {
        env->CallVoidMethod(activity, m);
    });
}

}  // namespace AndroidBridge

#endif  // __ANDROID__
