#pragma once
// TOUCH CONTROLS, at the same seam the keyboard and the gamepad already use.
//
// WHY THE TOUCH STATE ENDS UP HERE AND NOT IN THE RENDERER OR IN JAVA
// -------------------------------------------------------------------
// This runtime already has three input sources and one contract: whatever a device
// does, it becomes an XInput-shaped `HostPadState` published to pad 0, with the packet
// number moving only when the state changes (window.cpp's PublishPad, whose comment
// explains why a packet number that ticks every poll is a lie the title is entitled to
// believe). The keyboard merges into that; a physical controller merges into that. A
// finger on glass is a fourth device with the same output, so it takes the same path —
// and taking the same path is what makes it correct without a second theory of input:
// the guest sees one pad, the title's own deadzones and ramp apply as they always did,
// the input trace prints touch presses next to pad presses, and the PC-options panel
// (which reads pad-0 polls ONLY — the reason the keyboard had to stop being pad 2)
// works from a phone with no further work.
//
// WHAT IS NATIVE AND WHAT IS JAVA, and it is a deliberate split
// -------------------------------------------------------------
// The overlay's GEOMETRY lives in the app (android/.../TouchOverlayView.kt): which
// controls exist, where each one sits, how big it is, whether it is visible, and the
// edit mode that lets a player drag and resize them. All of that is Android View work —
// hit-testing, dp scaling, a settings UI, persistence — and doing it here instead would
// mean rasterising and blending translucent button art into the swapchain from a
// renderer whose present path deliberately has no blending (window.h's
// Host_DebugOverlayRender comment: "a blit is a copy, not a blend"). The app already has
// a compositor, a density and a canvas; this module has a pad contract to honour.
//
// So the app does the hit-testing and publishes ONE decoded snapshot per touch event,
// in XInput units, and this module owns everything guest-facing about it: whether touch
// is enabled at all, the merge into pad 0, the "no button may stay stuck" guarantees,
// and the counters that make a bug report about touch readable.
//
// THREADS. Publish is called from the app's UI thread (a JNI call, arriving whenever a
// finger moves); Read/Merge are called from the window thread inside its event loop, on
// the same turn that reads the controller and the keyboard. A mutex over a 16-byte
// struct, i.e. exactly what window.cpp already does for g_pads and for the same reason:
// the read rate is one per frame per source and the write rate is one per touch event,
// and neither is a hot path.
#include <atomic>
#include <cstdint>

// One decoded touch state, in XInput's units and sign conventions — the same ones
// HostPadState uses, NOT SDL's (see the PadAxisY note in window.cpp: SDL's stick Y
// points down, XInput's points up, and the overlay must produce XInput's).
struct TouchSnapshot
{
    uint16_t buttons = 0;        // XINPUT_GAMEPAD_* bits (window.cpp's XI_* names)
    uint8_t leftTrigger = 0;     // 0..255
    uint8_t rightTrigger = 0;    // 0..255
    int16_t thumbLX = 0, thumbLY = 0;   // -32768..32767, Y positive = up
    int16_t thumbRX = 0, thumbRY = 0;
    // Which controls produced this snapshot, for the trace and for the stuck-button
    // guarantee: a snapshot whose finger count is zero must be all-zero, and one that is
    // not is a leak in the overlay's hit-tester. Cheap to carry, and it turns "my
    // character walks left forever" from a debugging session into a log line.
    uint16_t activeFingers = 0;
};

// --- called from the app's UI thread (JNI, host/android_bridge.cpp) ---

// Publish a fresh snapshot. Idempotent and cheap; the merge side only moves the pad's
// packet number when something actually changed.
void TouchInput_Publish(const TouchSnapshot& s);

// Drop every held control to zero. Called when the activity pauses, when the surface is
// lost, when the overlay is hidden and when touch is switched off — the same guarantee
// window.cpp's keyboard-focus gating exists for. Without it, a finger that was holding
// RT when the player took a phone call is still holding RT when they come back, and the
// title has no way to learn otherwise: it polls a state, it does not see events.
void TouchInput_Clear();

// --- called from the window thread ---

// True when touch may contribute. False when CW_NO_TOUCH=1 (the whole-feature control
// arm, and the player-facing "disable touch controls" switch — see the note in
// touch_input.cpp on why the switch is an env var the app writes rather than a second
// settings key), or when the app has turned it off from its own UI.
bool TouchInput_Enabled();
void TouchInput_SetEnabled(bool on);

// Read the last published snapshot. Returns false when touch is disabled or nothing has
// ever been published — "disabled" and "enabled but idle" are different claims and the
// caller merges them differently (an idle overlay must not zero a drifting controller's
// axes the way an active one must; window.cpp's keyboard merge has that rule already).
bool TouchInput_Read(TouchSnapshot& out);

// The merge itself, kept here rather than inline in the event loop so that the rules and
// the reasoning stay next to each other.
//
// `pad` arrives holding the PHYSICAL CONTROLLER's state and nothing else — that ordering is
// load-bearing, see the drift rule below. Buttons OR; triggers take the larger; sticks take
// the larger magnitude per axis, so a Bluetooth pad and a thumb on a stick can both drive
// without either pinning the other.
//
// THE DRIFT RULE. window.cpp zeroes a controller's sub-deadzone axes whenever the keyboard
// is contributing, because an idle pad that reports 18% deflection (the operator's did)
// otherwise carries its drift into every key press and the character walks while the player
// types. A touch stick has the same problem with a Bluetooth pad, so the same rule is
// applied here — and applied to `pad` BEFORE the touch values are merged in, which is why
// the caller must hand us the controller's state alone. Zeroing afterwards would eat the
// touch stick's own deflection: unlike the keyboard's full-scale ±32767, a thumb on a stick
// publishes proportional values, and a gentle walk is exactly the sub-deadzone range the
// rule discards.
//
// `contributed` is set when the snapshot had anything in it, which is what tells the caller
// to flip the prompt art to the Xbox glyphs (touch emulates a pad, so the pad's icons are
// the honest ones) and to leave the keyboard's own drift rule alone.
void TouchInput_Merge(struct HostPadState& pad, bool& contributed);

// --- diagnostics ---

// Counters, printed at shutdown and by `--diag`. A phone port will be reported on from
// devices nobody on this project has, so "did the overlay publish anything at all, and
// how many presses per control" is the difference between a bug report that can be
// reasoned about and one that says "controls don't work".
struct TouchStats
{
    uint64_t publishes = 0;     // snapshots received from the app
    uint64_t merges = 0;        // snapshots that contributed to a pad state
    uint64_t clears = 0;        // stuck-button drops
    uint64_t buttonPresses = 0; // 0->1 transitions, all buttons
    uint64_t rejectedLeaks = 0; // snapshots with fingers but no state, or none but state
};
const TouchStats& TouchInput_Stats();
void TouchInput_ReportStats();
