#pragma once
// THE ANDROID BRIDGE: everything that crosses between the app's Java and this runtime.
//
// It is one file and one header on purpose, and the rule for what belongs here is narrow —
// a call that has to cross the language boundary. Everything else about Android lives
// where the thing it touches lives: paths in host/host_paths.cpp, the Vulkan loader in
// gpu/vk_shadow_android.cpp, the governor in host/android_perf.cpp, touch state in
// cpu/touch_input.cpp. A "android.cpp" that grows into the platform's whole implementation
// is how a port ends up with two places that decide the same thing.
//
// WHAT CROSSES, and in which direction:
//
//   Java -> native, before the guest starts:
//     * the data root, the driver choice and every setting, as ENVIRONMENT VARIABLES set
//       with android.system.Os.setenv. Not JNI, and that is the point: this runtime already
//       reads its configuration from the environment on every platform, the launcher's rows
//       and the developer's A/B arms are the same mechanism, and a second configuration
//       channel that only exists on one platform is a second place for them to disagree.
//     * SDL_main, the entry point SDLActivity's own nativeRunMain dlsyms out of our library.
//
//   Java -> native, while the game runs:
//     * decoded touch snapshots (cpu/touch_input.h says why the decoding is on the Java side)
//     * the OS's thermal status (host/android_perf.h)
//
//   native -> Java, while the game runs:
//     * the title's rumble, which reaches the phone's vibrator rather than a pad's motors.
//       That is the only one, and it is the reason this header exists at all: window.cpp's
//       IssueRumble has exactly one device behind it on every other platform, and on Android
//       the device is on the other side of JNI.
//     * the FIRST-RUN PROGRESS, which on a desktop is a small SDL window and on a phone has
//       to be the app's own UI — SDL's Android video driver owns exactly one surface, so
//       there is no second window to draw a progress bar into. window.cpp's
//       Host_ProgressBegin/Update/End forward here instead of creating anything.
#if defined(__ANDROID__)

#include <cstdint>

namespace AndroidBridge
{

// True once Java has handed us the Activity. Every native->Java call checks this rather
// than assuming: the runtime boots on a thread SDL created, and a rumble request that
// arrives before the Activity is registered (or after it is gone) must be dropped, not
// turned into a null jobject dereference inside the guest's own frame.
bool Ready();

// The title's two motor speeds, XInput units (0..65535). Called from window.cpp's rumble
// pump on the window thread, i.e. from a thread the JVM has never seen, so the
// implementation attaches and detaches around the call. Safe from any thread; a no-op
// before Ready() and after the Activity is released.
void Rumble(uint16_t leftMotor, uint16_t rightMotor);

// The first-run progress, forwarded from window.cpp's Host_Progress* trio.
//
// Begin returns FALSE when no Activity is listening, which is what makes the desktop
// behaviour fall out unchanged: main.cpp treats a false from Host_ProgressBegin as "console
// lines only" and carries on with the extract and the shader build either way, so a phone
// whose Activity is somehow not up yet still does the work — it just does not show a bar.
// Update is called at the rate the caller reports at (per file, per shader); the rate limit
// lives in window.cpp, next to the identical limit on the SDL path.
bool ProgressBegin(const char* title);
void ProgressUpdate(const char* line, float fraction);
void ProgressEnd();

}  // namespace AndroidBridge

#endif  // __ANDROID__
