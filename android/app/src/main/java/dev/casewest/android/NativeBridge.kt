package dev.casewest.android

/**
 * The whole native surface, in one file, because it is one contract.
 *
 * Every function here is `external` and resolves into libcw_runtime.so, whose JNI exports
 * are named `Java_dev_casewest_android_NativeBridge_native*` — so this object's package and
 * name are part of the ABI, and renaming either one produces an UnsatisfiedLinkError at the
 * first call rather than a compile error. runtime/host/android_bridge.h is the other half of
 * the same document and says what each of these is for; the comments below are the Kotlin
 * side of that conversation, and where the two disagree the C++ wins because it is the side
 * that has to be right at 3 a.m. on a device nobody on this project owns.
 *
 * WHICH DIRECTION EACH CALL GOES, since that is the part that is easy to get wrong:
 *
 *   these functions          Kotlin -> native. Called from the UI thread.
 *   onNativeRumble,          native -> Kotlin. Looked up by name on the ACTIVITY object
 *   onNativeProgressBegin,   handed to nativeSetActivity, which is why they are declared on
 *   onNativeProgress,        GameActivity and not here: the native side asks the JVM for
 *   onNativeProgressEnd      `GetMethodID(cls, "onNativeRumble", "(II)V")` on whatever class
 *                            it was given, and a method that lives only on this object
 *                            would not be found on that one.
 *
 * There is no `System.loadLibrary` here on purpose. Loading is GameActivity.loadLibraries's
 * job, because the environment has to be exported BEFORE the library is loaded and that
 * override is the one place in SDL's lifecycle guaranteed to run before it — see the comment
 * there. A second load call in an object initializer would be a second answer to "when does
 * this happen".
 */
object NativeBridge {

    /**
     * Hand the native side the Activity that should receive its callbacks.
     *
     * Called from GameActivity.onCreate AFTER super.onCreate (SDL loads the library in
     * there) and undone from onDestroy with [nativeClearActivity]. The native side keeps a
     * global reference, which is why clearing it is not optional: an Activity that is
     * finished but still referenced is a leaked window, and on a phone that leaks are
     * measured in the megabytes of the surface behind them.
     *
     * Passing an Activity whose class does not declare all four callbacks is allowed and
     * degrades per-callback — the native registration logs what it could not find and keeps
     * going, because "the progress bar never appeared" and "vibration does not work" are two
     * different reports with one cause and a partial registration is easier to diagnose than
     * a refused one.
     */
    external fun nativeSetActivity(activity: Any?)

    /** Drop the global reference taken by [nativeSetActivity]. Idempotent. */
    external fun nativeClearActivity()

    /**
     * Choose and load the Vulkan loader, and report whether one was found.
     *
     * Safe to call before the runtime starts — gpu/vk_shadow_android.cpp's InitLoader is a
     * call_once, and SDL_main calls it too. The launcher calls it so that its diagnostics row
     * can say which driver the game is about to get BEFORE the player commits to a 1.2 GB
     * first-run unpack, which is the difference between "this phone cannot run it" in five
     * seconds and the same sentence forty minutes later.
     */
    external fun nativeInitVulkan(): Boolean

    /**
     * What [nativeInitVulkan] ended up using, as one string for a bug report:
     * `adrenotools:<dir>/<name>` for a player-chosen driver, `system:<path>` for the device's
     * own, `none` for a device that has no Vulkan at all. Assembled natively because every
     * part of it is native state.
     */
    external fun nativeVulkanLoaderSource(): String

    /**
     * Publish one decoded touch state, in XInput's units.
     *
     * The decoding happens in [TouchOverlayView] and everything guest-facing happens in
     * runtime/cpu/touch_input.cpp; the split and the reasoning are in that file's header.
     * The units are the contract and they are XInput's, not SDL's:
     *
     *   [buttons]        XINPUT_GAMEPAD_* bits — the XI_* values in host/window.cpp
     *   [leftTrigger]    0..255
     *   [rightTrigger]   0..255
     *   [thumbLX]/[thumbLY]  -32768..32767, and Y POSITIVE IS UP. SDL's stick Y points
     *                        down; the overlay flips it, because a value the guest reads as
     *                        "down" when the thumb is up is a control that inverts itself
     *                        and looks like a bug in the game.
     *   [thumbRX]/[thumbRY]  the same, right stick
     *   [fingers]        how many controls produced this snapshot. A snapshot with fingers
     *                        but no state — or state but no fingers — is counted by the
     *                        native side as a rejected leak, which is what turns "my
     *                        character walks left forever" into one line in a log.
     *
     * Called from the UI thread on every touch event. Cheap by design: a mutex over a
     * 16-byte struct, on the same terms as the pad state window.cpp already publishes.
     */
    external fun nativeTouchPublish(
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        thumbLX: Int,
        thumbLY: Int,
        thumbRX: Int,
        thumbRY: Int,
        fingers: Int
    )

    /**
     * Drop every held control to zero. Called when the Activity pauses, when the overlay is
     * hidden and when touch is switched off: the title POLLS a state and never sees an
     * event, so a finger that was holding RT when the player took a phone call is still
     * holding RT when they come back unless somebody says otherwise.
     */
    external fun nativeTouchClear()

    /**
     * The player-facing "disable touch controls" switch, and the developer's CW_NO_TOUCH=1
     * arm, which are the same mechanism reached from two places. Off means the overlay
     * publishes nothing and the merge contributes nothing — a Bluetooth pad still works,
     * because touch is one input source among four and not a mode the runtime is in.
     */
    external fun nativeTouchSetEnabled(enabled: Boolean)

    /**
     * The OS's thermal status, as PowerManager's THERMAL_STATUS_* integer.
     *
     * host/android_perf.cpp's governor lowers the internal-resolution ceiling on MODERATE
     * and drops it to the floor on SEVERATE and above, which is the difference between a
     * phone that throttles itself and a phone that stutters until the OS throttles it.
     */
    external fun nativeSetThermalStatus(status: Int)

    /**
     * One block of text for the launcher's diagnostics screen: the loader in use, the touch
     * counters, the governor's ladder and its current rung. Built natively, because a screen
     * that shows numbers the Kotlin side had to guess at is worse than no screen.
     */
    external fun nativeRuntimeInfo(): String
}
