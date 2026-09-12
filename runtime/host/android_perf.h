#pragma once
// THE ANDROID PERFORMANCE LAYER: what a phone has that a desktop does not, and what this
// runtime does about it.
//
// WHY THIS IS A MODULE AND NOT A HANDFUL OF ENV VARS IN THE LAUNCHER.
// A desktop's performance problem is a settings problem: the machine either runs the game
// or it does not, the player picks a resolution once, and the frame rate that follows is
// a fact about their hardware. A phone is different in three specific ways, and each one
// needs code rather than a default:
//
//   1. THE BUDGET MOVES WHILE YOU PLAY. A Snapdragon at 20 °C and the same one at 45 °C
//      are not the same machine: the governor drops clocks, and Android tells the app
//      which stage it is in (PowerManager.THERMAL_STATUS_*, delivered to the Activity as
//      a listener and forwarded here over JNI). A resolution chosen at the menu is a
//      resolution chosen for a cold phone.
//   2. THE RIGHT ANSWER IS PER DEVICE AND UNKNOWABLE IN ADVANCE. Nobody porting this game
//      has an Adreno 730, an Adreno 619 and a Tensor G2 on the same desk, and the three
//      differ by more than a factor of two. What CAN be known is the frame rate, once per
//      second, from the loop that already counts it (window.cpp's title-bar update).
//   3. PIXELS ARE THE ONLY LEVER WITH RANGE. gpu/vk_renderer.cpp renders the guest's
//      1280x720 raster scaled RATIONALLY to an internal resolution (Settings_InternalRes),
//      live-applicable at a frame boundary (VkRenderer_RequestInternalRes, "callable from
//      any thread"). Frame cap is the player's choice, MSAA is next-launch-only, shadow
//      tier is a pipeline property — resolution is the one that can move mid-game and
//      buys the most per step, at ~2.25x fewer fragments from 1080-class to 720-class.
//
// So: a governor. It samples the frame rate once a second, moves the internal resolution
// DOWN a step when the target is missed for long enough to be a scene and not a spike, and
// back UP when there is headroom for long enough to be sure the headroom is real — bounded
// above by what the player chose and below by the smallest internal resolution the
// renderer's rational scalers accept. Thermal status moves the same bounds: a hot phone
// gets a lower ceiling and no climbing, because the alternative is a governor that
// oscillates against the kernel's own.
//
// WHAT IT NEVER DOES, deliberately:
//   * it never changes the player's PERSISTED settings. The ladder position is runtime
//     state; Settings_SetInternalRes is written only for the live apply, and the value the
//     launcher's resolution row shows stays the one the player picked.
//   * it never fights a measurement. CW_VK_RES / CW_VK_RES_SCALE pin the resolution for an
//     A/B (vk_renderer.h says the request is refused loudly then), so a pinned run disables
//     the governor and says so — an auto-scaler running inside a resolution experiment
//     would produce a number about the scaler.
//   * it never moves more than one step per sample, and never on the first sample. A
//     governor that reacts to the loading screen's frame time spends the whole game at the
//     floor having been briefly right once.
//
// EVERY KNOB HAS AN OFF SWITCH, because "the game got blurry and I did not ask for it" is
// a bug report that must be answerable in one line:
//   CW_ANDROID_AUTO_SCALE=0     the frame-rate governor off (thermal still applies)
//   CW_ANDROID_THERMAL=0        the thermal governor off (frame-rate still applies)
//   CW_ANDROID_PIN_BIG=1        pin the pump thread to the performance cluster (OFF by
//                               default — see android_perf.cpp for why that is not a
//                               measurement-free choice)
//   CW_ANDROID_TARGET_FPS=N     override the frame rate the governor aims at
#include <cstdint>

namespace AndroidPerf
{

// Called from Host_WindowInit on Android — after Settings_Load (the ladder is built from
// the player's persisted resolution) and before the guest starts (the first sample should
// be of gameplay, not of a boot). Idempotent; a no-op on every other platform.
void Init();

// One frame-rate sample, once a second, from window.cpp's existing title-bar cadence.
// The governor's only input besides thermal status.
void NoteFps(double fps);

// Android's PowerManager.THERMAL_STATUS_*, forwarded from the Activity's thermal listener:
// 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN. Values
// outside that range are clamped, because a status the OS invents should not become a
// resolution the governor invents.
void OnThermalStatus(int status);

// Pin the CALLING thread to the performance cluster. Off by default; see the .cpp for the
// reasoning and the launcher toggle that turns it on. Returns false when the device's
// cluster layout could not be read, which is the honest answer on a kernel that hides
// /sys/devices/system/cpu/*/cpufreq.
bool PinCurrentThreadToBigCores();

// The current ladder position and why: for the in-app overlay, `--diag` and the shutdown
// report. Cheap, and the difference between "the governor moved" and "the game got slow".
struct State
{
    bool enabled = false;          // the frame-rate governor
    bool thermalEnabled = false;   // the thermal half
    int thermalStatus = 0;         // the last one the OS reported
    uint32_t ceilingW = 0, ceilingH = 0;   // the player's choice; the ladder's top
    uint32_t currentW = 0, currentH = 0;   // what the renderer is rendering at now
    uint32_t floorW = 0, floorH = 0;       // the smallest valid internal resolution
    int steps = 0;                 // ladder size
    int step = 0;                  // 0 = at the ceiling
    uint64_t downMoves = 0, upMoves = 0;
    double lastFps = 0.0;
    double targetFps = 0.0;
};
State CurrentState();

// One block of lines at shutdown, printed whether or not the governor ever moved: a
// session that never scaled and one where scaling was disabled look identical in a log
// that only reports activity (gotcha 5's other half, and the reason every counter dump in
// this runtime prints unconditionally).
void Report();

}  // namespace AndroidPerf
