// The Android performance layer. See android_perf.h for the design and the three things a
// phone has that a desktop does not.
//
// COMPILES TO NOTHING OFF ANDROID, and is in the build on every platform so that the
// source list does not fork: the calls in window.cpp are inside #if defined(__ANDROID__),
// so a desktop binary contains these functions and never reaches them, and no measurement
// taken on Windows or Linux is about a different binary than before.
#include "android_perf.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <utility>
#include <vector>

#include "settings.h"
#include "../gpu/vk_renderer.h"

#if defined(__ANDROID__)
#include <dirent.h>
#include <sched.h>
#include <unistd.h>
#endif

namespace AndroidPerf
{

namespace
{

#if defined(__ANDROID__)

std::mutex g_mutex;
State g_state;
bool g_inited = false;

// The governor's hysteresis, in ONE-SECOND SAMPLES (NoteFps' cadence, which is
// window.cpp's title-bar cadence). These are the whole of "does not react to a spike":
// three seconds of missing the target is a scene, ten seconds of beating it is headroom.
// Asymmetric on purpose — dropping a step is cheap and reversible in one frame boundary,
// while climbing costs a frame rate the player is currently enjoying and, if wrong,
// immediately puts them back under.
constexpr int kDownSamples = 3;
constexpr int kUpSamples = 10;
// A step is a factor on HEIGHT, and 0.85 is ~28% fewer fragments per step. Coarse enough
// to be worth doing (a 5% step buys nothing measurable and moves the picture), fine
// enough that the floor is reachable in a few moves from any panel size.
constexpr double kStepFactor = 0.85;
// The renderer's own floor: internal resolution is a height of 720..2880 scaled
// rationally from the guest's 1280x720 raster (settings.h, "any height from 720 to
// 2880"), so 720 is the smallest thing that can be asked for and still be valid.
constexpr uint32_t kMinHeight = 720;

int g_downRun = 0;
int g_upRun = 0;
bool g_pinned = false;

// CW_VK_RES / CW_VK_RES_SCALE pin the resolution for a measurement run and the renderer
// refuses a live change loudly (vk_renderer.h). A governor that keeps asking and being
// refused would fill the log with refusals and still not be a control arm, so the pin is
// read once, here, and disables the governor.
bool ResolutionPinned(const char** which)
{
    if (const char* v = getenv("CW_VK_RES"); v && *v)
    {
        *which = "CW_VK_RES";
        return true;
    }
    if (const char* v = getenv("CW_VK_RES_SCALE"); v && *v)
    {
        *which = "CW_VK_RES_SCALE";
        return true;
    }
    return false;
}

// Is this feature switched OFF by the environment? "0" is the off spelling; anything
// else — including unset — leaves the default, which for both callers below is ON.
//
// NOTE THE DIRECTION, because the opposite spelling is also used in this runtime and
// getting them mixed up inverts a control arm silently. A variable named for what it
// DISABLES (CW_NO_TOUCH, CW_VK_NO_CUSTOM_DRIVER, CW_NO_WINDOW) is off-by-presence: setting
// it to anything turns the thing off, and "0" is the one value that means "no, stay on".
// A variable named for the FEATURE ITSELF (CW_ANDROID_AUTO_SCALE, CW_ANDROID_THERMAL, and
// CW_ANDROID_PIN_BIG a few lines below, which reads its own switch by hand) is the other
// way: unset is the default, "1" says yes, "0" says no.
//
// Applying the DISABLE-named idiom to a FEATURE-named variable is what this function used
// to do, and the effect was that CW_ANDROID_AUTO_SCALE=1 turned the frame-rate governor off
// while CW_ANDROID_AUTO_SCALE=0 turned it on — the exact inverse of the sentence this
// file prints at init ("CW_ANDROID_AUTO_SCALE=0 / CW_ANDROID_THERMAL=0 are the off
// switches") and of the row the launcher's settings screen offers. Both arms are ON by
// default and nobody sets them unless they mean to change something, so the defect was
// invisible until somebody read the log and noticed the governor's state disagreed with
// the variable that was supposed to decide it.
bool EnvSaysOff(const char* name)
{
    const char* v = getenv(name);
    return v && v[0] == '0';
}

// ---------------------------------------------------------------------------
// THE LADDER
// ---------------------------------------------------------------------------
// Every rung is a VALID internal resolution for this panel's aspect, computed once at
// init rather than per move: Settings_ValidInternalRes is not free, and a rung that fails
// validation mid-game would be a step the governor believes it took and the renderer
// refused.
std::vector<std::pair<uint32_t, uint32_t>> g_ladder;   // index 0 = the ceiling

void BuildLadder(uint32_t topW, uint32_t topH)
{
    g_ladder.clear();
    if (!topW || !topH || !Settings_ValidInternalRes(topW, topH))
        return;
    g_ladder.emplace_back(topW, topH);

    const double aspect = double(topW) / double(topH);
    uint32_t h = topH;
    // Bounded by the ladder's own geometry, not by a count: from 2880 to 720 at 0.85 a
    // step is eleven rungs, and a loop that cannot terminate is a loop someone has to
    // reason about at 3 a.m. on a phone that overheats.
    for (int i = 0; i < 16; ++i)
    {
        const uint32_t next = uint32_t(std::lround(double(h) * kStepFactor));
        if (next < kMinHeight || next >= h)
            break;
        h = next;
        uint32_t w = uint32_t(std::lround(double(h) * aspect)) & ~1u;   // width even
        if (!w || !Settings_ValidInternalRes(w, h))
        {
            // An aspect the scalers refuse (narrower than 16:10 for its height): stop
            // rather than invent a rung that would letterbox differently from the one
            // above it. The floor is then whatever we reached, which is honest.
            break;
        }
        g_ladder.emplace_back(w, h);
    }
}

void ApplyStepLocked(int step, const char* why)
{
    if (g_ladder.empty())
        return;
    if (step < 0)
        step = 0;
    if (step >= int(g_ladder.size()))
        step = int(g_ladder.size()) - 1;
    if (step == g_state.step && g_state.currentW && g_state.currentH)
        return;   // nothing to do; the renderer is already here

    const auto [w, h] = g_ladder[step];
    const bool down = step > g_state.step;
    // The persisted value is written because that is what the live apply reads back on a
    // swapchain rebuild, and because a resolution that is only in the renderer's pending
    // slot is one that a device-loss rebuild silently reverts. What is NOT written is the
    // player's CHOICE: the launcher's resolution row keeps showing ceilingW x ceilingH,
    // and the governor's position is reported separately (CurrentState).
    Settings_SetInternalRes(w, h);
    VkRenderer_RequestInternalRes(w, h);
    g_state.step = step;
    g_state.currentW = w;
    g_state.currentH = h;
    if (down)
        ++g_state.downMoves;
    else
        ++g_state.upMoves;

    fprintf(stderr,
            "[perf] internal resolution %ux%u -> %ux%u (%s, ladder rung %d of %zu, fps "
            "%.1f of target %.0f, thermal %d). CW_ANDROID_AUTO_SCALE=0 turns the "
            "frame-rate governor off; CW_ANDROID_THERMAL=0 the thermal one.\n",
            g_state.ceilingW, g_state.ceilingH, w, h, why, step + 1, g_ladder.size(),
            g_state.lastFps, g_state.targetFps, g_state.thermalStatus);
}

// The highest rung the current thermal status allows. A hot phone gets a lower ceiling and
// never climbs: the kernel is already throttling, and a governor that pushes resolution up
// into a throttle produces an oscillation neither side can win.
// The highest rung the current thermal status allows. A hot phone gets a lower ceiling and
// never climbs: the kernel is already throttling, and a governor that pushes resolution up
// into a throttle produces an oscillation neither side can win.
int ThermalCeilingStep()
{
    switch (g_state.thermalStatus)
    {
        case 0:   // THERMAL_STATUS_NONE
        case 1:   // LIGHT
        default:
            return 0;                       // the player's choice, unbounded
        case 2:   // MODERATE
            return 1;                       // one rung down at most
        case 3:   // SEVERE
        case 4:   // CRITICAL
        case 5:   // EMERGENCY
        case 6:   // SHUTDOWN
            return int(g_ladder.empty() ? 0 : g_ladder.size() - 1);   // the floor
    }
}

// ---------------------------------------------------------------------------
// CLUSTER DETECTION, for PinCurrentThreadToBigCores
// ---------------------------------------------------------------------------
// Read the per-CPU maximum frequency and take the top cluster. cpu_capacity is the better
// signal where a kernel exposes it (it accounts for IPC, not just clocks) but it is not
// readable on every device, so the frequency is the primary and capacity refines it when
// present. Both are /sys files a normal app can read on Android; neither is part of any
// API, and the failure mode of that is a pin we do not make — logged, not guessed.
struct CpuInfo
{
    int id = -1;
    long maxFreqKhz = 0;
    long capacity = 0;
};

std::vector<CpuInfo> ReadCpus()
{
    std::vector<CpuInfo> out;
    auto readLong = [](const char* path, long& v) {
        FILE* f = fopen(path, "r");
        if (!f)
            return false;
        const int n = fscanf(f, "%ld", &v);
        fclose(f);
        return n == 1;
    };
    for (int id = 0; id < 32; ++id)
    {
        char path[256];
        CpuInfo c;
        c.id = id;
        snprintf(path, sizeof path,
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", id);
        const bool haveFreq = readLong(path, c.maxFreqKhz);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpu_capacity", id);
        readLong(path, c.capacity);
        if (!haveFreq && !c.capacity)
            break;   // no such CPU (or no readable topology): the list ends here
        out.push_back(c);
    }
    return out;
}

#endif  // __ANDROID__

}  // namespace

void Init()
{
#if defined(__ANDROID__)
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_inited)
        return;
    g_inited = true;

    uint32_t w = 0, h = 0;
    Settings_InternalRes(w, h);
    g_state.ceilingW = g_state.currentW = w;
    g_state.ceilingH = g_state.currentH = h;

    // The frame rate to aim at is the player's cap when they set one, and the console's
    // own 30 when they did not: this title ran at 30 on hardware, the port surfaces 60 as
    // an option, and a phone with no cap set has no business being governed towards an
    // uncapped frame rate it cannot reach.
    const int cap = Settings_FpsCap();
    double target = cap > 0 ? double(cap) : 30.0;
    if (const char* t = getenv("CW_ANDROID_TARGET_FPS"); t && *t)
    {
        const double parsed = atof(t);
        if (parsed >= 10.0 && parsed <= 240.0)
            target = parsed;
        else
            fprintf(stderr, "[perf] CW_ANDROID_TARGET_FPS=%s is not 10..240 — ignored, "
                            "aiming at %.0f.\n", t, target);
    }
    g_state.targetFps = target;

    const char* pinnedBy = nullptr;
    if (ResolutionPinned(&pinnedBy))
    {
        fprintf(stderr,
                "[perf] %s pins the internal resolution for a measurement run, so the "
                "Android governor is DISABLED: an auto-scaler inside a resolution "
                "experiment measures the scaler. Unset it to govern.\n", pinnedBy);
        return;
    }

    BuildLadder(w, h);
    g_state.steps = int(g_ladder.size());
    g_state.floorW = g_ladder.empty() ? 0 : g_ladder.back().first;
    g_state.floorH = g_ladder.empty() ? 0 : g_ladder.back().second;
    g_state.enabled = !EnvSaysOff("CW_ANDROID_AUTO_SCALE");
    g_state.thermalEnabled = !EnvSaysOff("CW_ANDROID_THERMAL");

    if (g_ladder.size() < 2)
    {
        fprintf(stderr,
                "[perf] no ladder below %ux%u (the renderer's floor is a 720-high internal "
                "resolution and the panel is already at or under it) — the governor has "
                "nothing to move and is idle.\n", w, h);
        g_state.enabled = false;
        return;
    }

    fprintf(stderr,
            "[perf] Android governor: ceiling %ux%u, floor %ux%u, %d rungs at %.2f of the "
            "height per step, target %.0f fps, frame-rate arm %s, thermal arm %s. "
            "CW_ANDROID_AUTO_SCALE=0 / CW_ANDROID_THERMAL=0 are the off switches.\n",
            g_state.ceilingW, g_state.ceilingH, g_state.floorW, g_state.floorH,
            g_state.steps, kStepFactor, g_state.targetFps,
            g_state.enabled ? "ON" : "OFF", g_state.thermalEnabled ? "ON" : "OFF");

    const char* pin = getenv("CW_ANDROID_PIN_BIG");
    if (pin && *pin && *pin != '0')
        PinCurrentThreadToBigCores();   // this thread is the pump thread: see the .h
#endif
}

void NoteFps(double fps)
{
#if defined(__ANDROID__)
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_inited || !g_state.enabled || g_ladder.size() < 2)
        return;
    g_state.lastFps = fps;

    // A frame rate of zero is a paused or backgrounded app, not a slow one: scaling down
    // for a phone in somebody's pocket is how a governor earns a reputation for ruining
    // the next session. The sample is dropped, and both runs reset with it.
    if (fps <= 0.0)
    {
        g_downRun = g_upRun = 0;
        return;
    }

    const double target = g_state.targetFps;
    const int ceiling = g_state.thermalEnabled ? ThermalCeilingStep() : 0;

    if (fps < target * 0.85 && g_state.step < int(g_ladder.size()) - 1)
    {
        ++g_downRun;
        g_upRun = 0;
        if (g_downRun >= kDownSamples)
        {
            g_downRun = 0;
            char why[64];
            snprintf(why, sizeof why, "missed target %d s running", kDownSamples);
            ApplyStepLocked(g_state.step + 1, why);
        }
        return;
    }

    if (fps > target * 1.05 && g_state.step > ceiling)
    {
        ++g_upRun;
        g_downRun = 0;
        if (g_upRun >= kUpSamples)
        {
            g_upRun = 0;
            char why[64];
            snprintf(why, sizeof why, "headroom %d s running", kUpSamples);
            ApplyStepLocked(g_state.step - 1, why);
        }
        return;
    }

    g_downRun = g_upRun = 0;
#else
    (void)fps;
#endif
}

void OnThermalStatus(int status)
{
#if defined(__ANDROID__)
    std::lock_guard<std::mutex> lock(g_mutex);
    if (status < 0)
        status = 0;
    if (status > 6)
        status = 6;
    if (!g_inited)
        return;
    const int prev = g_state.thermalStatus;
    g_state.thermalStatus = status;
    if (prev == status)
        return;

    static const char* kNames[] = { "NONE", "LIGHT",     "MODERATE", "SEVERE",
                                    "CRITICAL", "EMERGENCY", "SHUTDOWN" };
    fprintf(stderr, "[perf] thermal status %s -> %s (Android PowerManager)\n",
            kNames[prev], kNames[status]);

    if (!g_state.thermalEnabled)
        return;
    if (g_ladder.size() < 2)
        return;

    // Move to the new ceiling if we are above it. Never move UP on a thermal change alone:
    // the frame-rate arm climbs, slowly, with evidence; the thermal arm only clamps.
    const int ceiling = ThermalCeilingStep();
    if (g_state.step < ceiling)
        ApplyStepLocked(ceiling, "thermal ceiling");
#else
    (void)status;
#endif
}

bool PinCurrentThreadToBigCores()
{
#if defined(__ANDROID__)
    const std::vector<CpuInfo> cpus = ReadCpus();
    if (cpus.size() < 2)
    {
        fprintf(stderr, "[perf] CPU topology unreadable — not pinning. This is a kernel "
                        "that hides /sys/devices/system/cpu/*/cpufreq from apps, not a "
                        "failure of the request.\n");
        return false;
    }

    // The top cluster: every CPU whose score is within 5% of the best one. Capacity is the
    // score where the kernel publishes it and max frequency otherwise — and mixing the two
    // (capacity for some CPUs, frequency for others) would compare incomparable numbers,
    // so the choice is made once for the whole device.
    const bool useCapacity = std::all_of(cpus.begin(), cpus.end(),
                                         [](const CpuInfo& c) { return c.capacity > 0; });
    long best = 0;
    for (const auto& c : cpus)
        best = std::max(best, useCapacity ? c.capacity : c.maxFreqKhz);
    if (best <= 0)
        return false;

    cpu_set_t set;
    CPU_ZERO(&set);
    int count = 0;
    for (const auto& c : cpus)
    {
        const long score = useCapacity ? c.capacity : c.maxFreqKhz;
        if (score * 20 >= best * 19)   // within 5% of the best
        {
            CPU_SET(c.id, &set);
            ++count;
        }
    }
    if (count == 0 || count == int(cpus.size()))
    {
        // Either nothing qualified, or everything did — a single-cluster device, where
        // pinning is a restriction with no upside.
        fprintf(stderr,
                "[perf] no distinct performance cluster (%d of %zu CPUs qualified, score "
                "source %s) — not pinning.\n",
                count, cpus.size(), useCapacity ? "cpu_capacity" : "cpuinfo_max_freq");
        return false;
    }

    if (sched_setaffinity(0, sizeof set, &set) != 0)
    {
        fprintf(stderr, "[perf] sched_setaffinity to the %d performance CPU(s) failed — "
                        "not pinned.\n", count);
        return false;
    }
    if (!g_pinned)
    {
        g_pinned = true;
        fprintf(stderr,
                "[perf] this thread pinned to %d of %zu CPUs (the performance cluster, by "
                "%s). CW_ANDROID_PIN_BIG is OFF by default and this line only appears when "
                "it is on: pinning is a real lever on a big.LITTLE phone and an unmeasured "
                "one in this port, so it is a launcher toggle rather than a default — see "
                "docs/android-port-plan.md, milestone A6's owed measurement.\n",
                count, cpus.size(), useCapacity ? "cpu_capacity" : "cpuinfo_max_freq");
    }
    return true;
#else
    return false;
#endif
}

State CurrentState()
{
#if defined(__ANDROID__)
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state;
#else
    return State{};
#endif
}

void Report()
{
#if defined(__ANDROID__)
    const State s = CurrentState();
    fprintf(stderr,
            "[perf] Android governor: frame-rate arm %s, thermal arm %s, thermal %d, "
            "ladder %d rungs %ux%u..%ux%u, now at rung %d (%ux%u), target %.0f fps, last "
            "%.1f fps, %llu step(s) down and %llu up.\n",
            s.enabled ? "ON" : "off", s.thermalEnabled ? "ON" : "off", s.thermalStatus,
            s.steps, s.ceilingW, s.ceilingH, s.floorW, s.floorH, s.step + 1, s.currentW,
            s.currentH, s.targetFps, s.lastFps, (unsigned long long)s.downMoves,
            (unsigned long long)s.upMoves);
#endif
}

}  // namespace AndroidPerf
