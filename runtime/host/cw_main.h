#pragma once
// The runtime's entry point, as a FUNCTION rather than as `main`.
//
// WHY THIS HEADER EXISTS. On Windows and Linux the runtime is an executable and the OS
// calls `main`. On Android the runtime is a SHARED LIBRARY inside an APK: the framework
// owns the process, an Activity owns the main thread, and the guest is started from JNI
// on a thread that library spawns (host/android_bridge.cpp). A body that can only be
// entered as `main` cannot be entered at all in that shape, and the alternative — two
// copies of the boot sequence behind an #ifdef — is exactly the drift this project
// spends its time undoing: the desktop boot is measured and gated, the phone boot would
// be neither, and every fix would have to be made twice to mean the same thing.
//
// So: ONE body, named, and each platform supplies the one-line wrapper its loader
// expects. main.cpp defines it; the desktop wrapper is at the bottom of that file and
// the Android wrapper is the JNI function in android_bridge.cpp.
//
// The return value is the process exit code on desktop. On Android it is returned to the
// bridge, which logs it and finishes the Activity — a phone has no exit status for
// anybody to read, and a boot that returned 1 must still SAY so rather than leave the
// player looking at a frozen surface.
int CwRuntimeMain(int argc, char** argv);
