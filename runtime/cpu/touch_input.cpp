// Touch controls: the guest-facing half. See touch_input.h for the split — geometry and
// hit-testing live in the app, the pad contract lives here.
#include "touch_input.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

#include "../host/window.h"   // HostPadState, and the XI_* button names below

namespace
{

std::mutex g_mutex;
TouchSnapshot g_snapshot;
bool g_everPublished = false;
// The buttons held in the PREVIOUS snapshot, so a press can be counted as the 0->1
// transition it is rather than as every publish of a held finger (a thumb resting on A
// publishes at 60-120 Hz; a counter that incremented per publish would report 3,000
// presses for one hold and be useless as evidence).
uint16_t g_prevButtons = 0;
TouchStats g_stats;

// WHY THE SWITCH IS AN ENV VAR THE APP WRITES, AND NOT A SECOND SETTINGS KEY.
//
// host/settings.cpp already persists the graphics rows, and "touch controls on/off"
// looks like one more row. It is not, for a reason that only shows up on a phone: the
// player who needs touch OFF needs it off before the guest boots, because the very first
// thing the title does is read pad 0 — a toggle applied from an in-game menu would leave
// the boot and the title screen on touch and the gameplay not, which is the one shape of
// half-applied setting this project keeps finding (part 54's note: SDL_WINDOW_VULKAN and
// fullscreen are creation-time decisions; this is an input decision with the same
// property). So the launcher writes CW_NO_TOUCH into the process environment before
// SDL_main runs, exactly as it writes CW_VK_RES and CW_FPS_CAP, and one mechanism covers
// every setting that has to be decided at boot. TouchInput_SetEnabled below is the
// in-session override the overlay's own master switch uses, and it deliberately does NOT
// write the file: a mid-session change that survives a relaunch is a surprise.
bool EnvSaysOff()
{
    const char* e = getenv("CW_NO_TOUCH");
    return e && *e && *e != '0';
}

std::atomic<bool> g_enabled{ true };
bool g_envChecked = false;

// XInput's button bits, restated. window.cpp has these as XI_* inside its own anonymous
// namespace, which is the right home for them there and the wrong one to reach across
// into: an anonymous namespace is not an interface. The values are XInput's, i.e. ABI,
// and the app's overlay produces exactly these bits.
constexpr uint16_t kA = 0x1000, kB = 0x2000, kX = 0x4000, kY = 0x8000;
constexpr uint16_t kStart = 0x0010, kBack = 0x0020;
constexpr uint16_t kDpadUp = 0x0001, kDpadDown = 0x0002, kDpadLeft = 0x0004,
                   kDpadRight = 0x0008;
constexpr uint16_t kLThumb = 0x0040, kRThumb = 0x0080, kLB = 0x0100, kRB = 0x0200;
constexpr uint16_t kAllButtons = kA | kB | kX | kY | kStart | kBack | kDpadUp | kDpadDown |
                                 kDpadLeft | kDpadRight | kLThumb | kRThumb | kLB | kRB;

bool SnapshotHasState(const TouchSnapshot& s)
{
    return s.buttons || s.leftTrigger || s.rightTrigger || s.thumbLX || s.thumbLY ||
           s.thumbRX || s.thumbRY;
}

// One XInput axis taken by magnitude, sign kept. "Larger wins" rather than "newest wins"
// is the rule window.cpp already applies when the keyboard merges into a physical pad, and
// for the same reason: with two devices driving one stick, newest-wins makes the stick
// oscillate between them at the publish rate, which reads as the game fighting the player.
int16_t ByMagnitude(int16_t current, int16_t incoming)
{
    const int32_t a = current < 0 ? -int32_t(current) : int32_t(current);
    const int32_t b = incoming < 0 ? -int32_t(incoming) : int32_t(incoming);
    return b > a ? incoming : current;
}

}  // namespace

void TouchInput_Publish(const TouchSnapshot& s)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_stats.publishes;

    // THE LEAK CHECK, and it is the reason activeFingers is in the snapshot at all.
    // Two impossible states, both of which mean the app's hit-tester lost track of a
    // finger, and both of which present to a player as a control that will not let go:
    //   fingers down but nothing held  — a press the overlay did not decode (harmless
    //                                    to the guest, but the player sees a dead button)
    //   nothing down but something held — THE STUCK BUTTON. Published once, and the
    //                                    guest keeps seeing it forever, because it polls.
    // The second one is corrected here rather than only counted: dropping a state nobody
    // is holding is what TouchInput_Clear does anyway, and doing it at the moment the
    // contradiction is visible beats waiting for a pause that may not come.
    if (s.activeFingers == 0 && SnapshotHasState(s))
    {
        ++g_stats.rejectedLeaks;
        if (g_stats.rejectedLeaks <= 8)
            fprintf(stderr,
                    "[touch] publish with ZERO fingers but state buttons=%04X LT=%u RT=%u "
                    "L=(%d,%d) R=(%d,%d) — DROPPED. The overlay published a held control "
                    "after its finger went up; that is a stuck button, and the guest polls "
                    "rather than sees events, so it would never have gone away on its own.\n",
                    s.buttons, s.leftTrigger, s.rightTrigger, s.thumbLX, s.thumbLY,
                    s.thumbRX, s.thumbRY);
        TouchSnapshot zero{};
        g_snapshot = zero;
        g_prevButtons = 0;
        return;
    }

    // Count 0->1 transitions only.
    const uint16_t pressed = uint16_t(s.buttons & ~g_prevButtons);
    for (uint16_t bit = 1; bit; bit = uint16_t(bit << 1))
        if (pressed & bit)
            ++g_stats.buttonPresses;

    g_snapshot = s;
    g_snapshot.buttons &= kAllButtons;   // the guide bit and anything above it are not ours
    g_prevButtons = g_snapshot.buttons;
    g_everPublished = true;
}

void TouchInput_Clear()
{
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_stats.clears;
    g_snapshot = TouchSnapshot{};
    g_prevButtons = 0;
}

bool TouchInput_Enabled()
{
    if (!g_envChecked)
    {
        g_envChecked = true;
        if (EnvSaysOff())
        {
            g_enabled.store(false, std::memory_order_relaxed);
            fprintf(stderr,
                    "[touch] CW_NO_TOUCH=1 — touch controls are OFF for this run. The "
                    "overlay publishes nothing, the merge below contributes nothing, and a "
                    "Bluetooth pad is the only input. This is both the whole-feature "
                    "control arm and the player-facing switch (see touch_input.cpp's note "
                    "on why it is decided at boot rather than in a menu).\n");
        }
    }
    return g_enabled.load(std::memory_order_acquire);
}

void TouchInput_SetEnabled(bool on)
{
    const bool was = g_enabled.exchange(on, std::memory_order_acq_rel);
    if (was == on)
        return;
    if (!on)
        TouchInput_Clear();   // a switch turned off while a finger holds RT must release it
    fprintf(stderr, "[touch] controls %s%s\n", on ? "ENABLED" : "DISABLED",
            EnvSaysOff() ? " (note: CW_NO_TOUCH=1 is also set, and the environment wins "
                         "at boot — the launcher is what clears it)"
                         : "");
}

bool TouchInput_Read(TouchSnapshot& out)
{
    if (!TouchInput_Enabled())
        return false;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_everPublished)
        return false;
    out = g_snapshot;
    return true;
}

void TouchInput_Merge(HostPadState& pad, bool& contributed)
{
    contributed = false;
    TouchSnapshot s;
    if (!TouchInput_Read(s))
        return;
    if (!SnapshotHasState(s))
        return;   // enabled and idle: nothing to merge, and no deadzone rule to apply

    contributed = true;

    // The drift rule — see the header. Applied to `pad` while it still holds ONLY the
    // physical controller's contribution, and before any touch value is merged into it.
    // 7849 is the XInput reference deadzone window.cpp uses for the same rule; the guest
    // applies its own deadzone on top, so a value inside this window is drift by
    // definition and not a deflection anybody meant.
    auto zeroDrift = [](int16_t& v) {
        if (v > -7849 && v < 7849)
            v = 0;
    };
    zeroDrift(pad.thumbLX);
    zeroDrift(pad.thumbLY);
    zeroDrift(pad.thumbRX);
    zeroDrift(pad.thumbRY);

    pad.buttons |= s.buttons;
    if (s.leftTrigger > pad.leftTrigger)
        pad.leftTrigger = s.leftTrigger;
    if (s.rightTrigger > pad.rightTrigger)
        pad.rightTrigger = s.rightTrigger;
    pad.thumbLX = ByMagnitude(pad.thumbLX, s.thumbLX);
    pad.thumbLY = ByMagnitude(pad.thumbLY, s.thumbLY);
    pad.thumbRX = ByMagnitude(pad.thumbRX, s.thumbRX);
    pad.thumbRY = ByMagnitude(pad.thumbRY, s.thumbRY);

    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_stats.merges;
}

const TouchStats& TouchInput_Stats() { return g_stats; }

void TouchInput_ReportStats()
{
    // Printed whether or not anything happened, because "the overlay never published a
    // single snapshot" is the finding — a silent zero and a missing feature look the same
    // in a log that only prints non-zero counters (gotcha 5, and the reason every counter
    // dump in this runtime prints unconditionally).
    const TouchStats& s = g_stats;
    fprintf(stderr,
            "[touch] publishes %llu  merged %llu  clears %llu  button presses %llu  "
            "contradictions dropped %llu  enabled %s\n",
            (unsigned long long)s.publishes, (unsigned long long)s.merges,
            (unsigned long long)s.clears, (unsigned long long)s.buttonPresses,
            (unsigned long long)s.rejectedLeaks, TouchInput_Enabled() ? "yes" : "NO");
}
