# The Android port

This is the plan of record for running Dead Rising 2: Case West on a phone, and the place the
code points at when it needs to say *why* rather than *what*. Thirteen comments across
`runtime/`, `tools/` and `android/` name a section of this file; the section numbers below are
therefore part of the interface and should not be renumbered casually.

Status: **milestones A0–A5 implemented, A6 owed a measurement.** Nothing here has yet been run
on a device by this project — see §11 for exactly what has been proven and by what.

---

## 0. The premise

The runtime is not being ported. It is being *built for* another platform, which is a different
job with a different failure mode.

`runtime/` is ~76,700 lines of C++20 that already runs on Linux and Windows, and the guest image
under it is generated C++ that XenonRecomp produces for any host clang supports — including
AArch64, where its `ppc_context.h` routes the guest's VMX unit through simde onto NEON. So the
work is not "rewrite the emulator". It is:

* the ~30 host sources, checked one at a time for the assumptions a phone breaks (paths, memory,
  the entry point, the window, the loader);
* four new files that hold everything Android-specific;
* a build that produces one shared library instead of one executable;
* an app around it, because Android has no process to exec.

The discipline that follows from that framing: **the source list does not fork.** One
`CW_RUNTIME_SOURCES` in `runtime/CMakeLists.txt`, one set of feature switches, and the four
Android files compiled on every platform where they compile to nothing. A port that builds a
slightly different game per platform is a port whose desktop measurements stop being evidence
about anything.

## 1. What is Android-specific, and where each part lives

Deliberately scattered, and the rule is that a thing lives where the thing it touches lives:

| File | What it owns |
|---|---|
| `runtime/host/android_bridge.{h,cpp}` | Everything that crosses the language boundary: the JNI callbacks, `SDL_main`, and stdout/stderr → logcat |
| `runtime/host/android_perf.{h,cpp}` | The governor: the internal-resolution ladder, the frame-rate arm, the thermal arm, big-core pinning |
| `runtime/cpu/touch_input.{h,cpp}` | Touch as the fourth input source: the merge into pad 0, the stuck-button guarantees, the counters |
| `runtime/gpu/vk_shadow_android.{h,cpp}` | The Vulkan loader table, so the renderer needs no linked `libvulkan` (§5) |
| `android/` | The app: launcher, game activity, touch overlay, driver import |
| `tools/android/` | Cross-compiling the four dependencies and packaging the APK |

An `android.cpp` that grew into the platform's whole implementation is how a port ends up with
two places that decide the same thing. `android_bridge.h`'s header comment states the rule and
the reason.

## 2. The entry point is a named function, not `main`

`runtime/main.cpp` puts the whole boot in `CwRuntimeMain(argc, argv)` and gives each platform a
one-line wrapper: `main()` on the desktops (inside `#ifndef __ANDROID__`), `SDL_main()` on
Android (in `android_bridge.cpp`).

Why not `#ifdef` the signature. On Windows and Linux this program *is* the process: the OS starts
it at `main` and the window loop owns the process until it exits. On Android there is no process
to own — the framework started it, an Activity's Java is already on the main thread, and the
runtime is a shared library that `SDLActivity.nativeRunMain` dlopens and calls `SDL_main` in.
Keeping the body in a function means the two entry paths cannot drift: whatever a desktop run does
at boot, a phone run does too, in the same order, from the same code. `host/cw_main.h` declares it
so the bridge does not re-declare it by hand.

Two things happen in `SDL_main` before `CwRuntimeMain`, and both have to be first:

1. `RedirectStdioToLogcat()` — a non-debuggable app's stdout *and* stderr are `/dev/null`. The
   runtime's entire diagnostic surface is `fprintf(stderr, ...)`, and the `--smoke` harness's
   verdict is a `printf`, so mirroring only stderr would give CI a gate that prints its answer to
   nowhere. `host/log_file.cpp` is unchanged: it dup2's stderr into its own pipe and keeps a dup
   of the original to echo to, so the tee's "console copy" *is* logcat, and the module that owns
   the logging policy never had to learn about Android.
2. `CwVk::InitLoader()` — the driver decision has to be made before the first `vkCreateInstance`,
   and doing it here rather than lazily puts the `[vk-loader]` lines at the top of the boot log,
   above the renderer's own, which is where somebody reading a phone's log for "which driver was
   it" looks.

## 3. Dependencies, and what each prefix must contain

Nothing on a phone is installed. Four things are cross-compiled by `tools/android/*.sh` into
`third_party/android/<abi>/`, and `runtime/CMakeLists.txt` refuses at *configure* time when one is
missing — the same discipline as the desktop build's SDL2/Vulkan/ffmpeg checks, because a missing
dependency should be a build message naming what to build, not a wall of undefined symbols after
228 translation units have compiled.

| Prefix | Script | Contains |
|---|---|---|
| `sdl2/` | `build_sdl2_android.sh` | `lib/libSDL2.a`, `include/SDL2/`, `lib/cmake/SDL2/` with an `SDL2::SDL2-static` target |
| `ffmpeg/` | `build_ffmpeg_android.sh` | `lib/libavcodec.a`, `lib/libavutil.a`, `include/libavcodec/avcodec.h`, LGPL, xma1+xma2 only |
| `adrenotools/` | `build_adrenotools.sh` | `include/adrenotools/{driver,bcenabler}.h`, `lib/libadrenotools.a`, `jniLibs/` with all four hook `.so` files |
| `<XenonRecomp>/build-android-<abi>/` | `build_xenon_android.sh` | `XenonUtils/libXenonUtils.a`, `thirdparty/fmt/libfmt.a`, `thirdparty/xxHash/cmake_unofficial/libxxhash.a` |

Plus `third_party/android/sdl-java/` — SDL's `org/libsdl/app/*.java`, copied by the same script
that builds the library, from the same tree. **The two must come from one source tree**:
`SDLActivity.onCreate` compares its own `SDL_MAJOR/MINOR/MICRO` constants against the library's
`nativeGetVersion()` and refuses to start on a mismatch. That is correct behaviour which reads
like a broken build if you compiled the `.a` from one tag and copied the `.java` from another.

Three notes that cost time to learn and are therefore written down:

* **SDL2 is static, and `SDL2main` is never linked.** An APK cannot carry a `libSDL2.so` the
  framework did not put there without shipping it, and shipping two copies of SDL means two
  answers to "which surface exists". `SDL2main` exists to provide a `main` that forwards to
  `SDL_main`; on Android `SDLActivity` calls `SDL_main` directly, and on the desktops `main.cpp`
  already *is* that `main`. Linking it would put two definitions of `main` on one link line.
* **XenonRecomp's arm64 tree is not its `build/` tree.** `build/` holds the *host* executable that
  generates `ppc/`. A static library is not portable between architectures, so cross-building into
  `build/` would replace the generator with a binary the build machine cannot run.
* **adrenotools is optional and its absence is honest.** `gpu/vk_shadow_android.cpp` wraps every
  use in `#if defined(CW_HAVE_ADRENOTOOLS)`; without it the runtime builds, runs, uses the system
  driver, and says so in the boot log. `app/build.gradle.kts` turns the same fact into a
  `BuildConfig.HAVE_ADRENOTOOLS` field so the launcher's driver row says "unavailable in this
  build" instead of offering something that cannot work.

`CW_MIN_ANDROID_API` is **26** (Android 8.0). That is the runtime's floor, and it is lower than
the floor of some of its features: adrenotools wants 28+ and works best on 29+ (below 29 there is
no memfd for it to patch libraries through), the thermal listener is 29+, and Vulkan 1.3 from the
*platform* loader is 33+. Each of those degrades separately and says so; see §5 and §7.

## 4. The app

Two activities and a service, and the split is structural rather than aesthetic: `SDLActivity`'s
main thread does not return from `nativeRunMain` until the runtime exits, so any UI that has to be
usable *before* a boot cannot live in the Activity that boots.

* **`LauncherActivity`** — the launcher. Four numbered sections in the order a first launch has to
  happen in: the game file, the GPU driver, performance, touch. Plus a diagnostics panel and the
  Play button. Everything persists immediately; there is no Apply button, because a settings screen
  whose changes are lost by a back press is a settings screen that gets reported as broken.
* **`GameActivity extends SDLActivity`** — the surface, and four additions to it: the environment
  export, the touch overlay, the first-run progress UI, and rumble.
* **`TouchSettingsActivity`** — per-control visibility and size, and the master disable.
* **`ProbeService`** — asks the runtime which driver it would load, in **its own process**
  (`android:process=":probe"`). `CwVk::InitLoader` is a `std::once_flag` and a loaded library
  stays loaded in its process, so a launcher that probed in the app's own process would *decide*
  the driver for the game that follows it. The answer travels as a file the player can be asked to
  send.

**The configuration channel is `setenv`.** Every setting reaches the runtime as an environment
variable — the same ones a developer types to run a control arm — exported by
`GameActivity.loadLibraries()` before `System.loadLibrary`. A second, Android-only channel would
be a second place for the launcher's rows and the developer's A/B arms to disagree. Order matters
twice: the data root is created and seeded *before* `CW_ROOT` is exported (`host_paths.cpp`
resolves it with `is_directory` and ignores a path that does not exist yet), and the export happens
before the load (several variables are read once behind a `once_flag`).

**One library, one name.** `getLibraries()` returns `{"cw_runtime"}`, which is what
`getMainSharedObject()` turns into `libcw_runtime.so` — the same string `CW_ANDROID_SELF_LIB` in
`window.cpp` spells with the platform's prefix and suffix, and the same name CMake gives the
target. Three places that must agree, so the name is the target's own and not a fourth literal.

## 5. The GPU: the driver is not the system one

This is the section that decides whether the port is playable, and it has three parts.

### 5.1 Why the runtime cannot link `libvulkan`

Every other platform links `Vulkan::Vulkan` and calls `vkCmdBlitImage(...)`; the symbol resolves
against the loader the OS installed, which talks to the driver the OS installed. Android breaks the
second half. The loader is `/system/lib64/libvulkan.so` and the driver is
`/vendor/lib64/hw/vulkan.adreno.so`, and neither is anything an app can influence —
`VK_DRIVER_FILES` is honoured by Android's loader only for debuggable apps, and a release APK is
not one.

`libadrenotools` is the rootless answer: it loads the system loader into a private linker namespace
with a hook that redirects the loader's own driver `dlopen` at a file in the app's storage, and
hands back a `void*` to that hooked loader. Every Vulkan call must then go through that handle,
because a second, differently-loaded copy of libvulkan is a second loader with a second driver
table — i.e. the system driver, i.e. the thing we were trying not to use.

So the runtime **does not link libvulkan on Android**, and `runtime/CMakeLists.txt` says so in a
`message(STATUS)` rather than quietly skipping the `find_package`. The headers come from the NDK's
sysroot; the entry points come from a table.

### 5.2 The shadow table

`gpu/vk_shadow_android.h` is generated by `tools/gen_vk_shadow.py`, which **scans the runtime's own
sources for call sites** — the same method `tools/gen_stub_ppc.py` uses to find the guest functions
the host references — and emits 95 slots plus the `#define` shadows that bind existing call sites
to them. `vk_renderer.cpp` gained exactly one line: `#include "vk_shadow_android.h"` after
`<vulkan/vulkan.h>`. The include order is load-bearing (the header includes `vulkan.h` first so the
guard is set before the macros exist) and is the same trick `cpu/timebase.h` uses for `__rdtsc`.

The fill is two-phase: `dlsym` for all 95 names, then a `vkGetInstanceProcAddr` backfill for the
ones the loader does not export globally. That second phase is not paranoia — Android's libvulkan
exports core entry points only up to the API version *that Android* supports, so `vkCmdBeginRendering`
as a global symbol fails on Android 12 and below while the driver behind it may well have it.

`vk_shadow_android.cpp` is compiled with `CWVK_NO_SHADOW` and exports five symbols as `extern "C"`:
`vkGetInstanceProcAddr`, `vkCreateInstance`, `vkEnumerateInstanceExtensionProperties`,
`vkEnumerateInstanceVersion`, `vkEnumerateInstanceLayerProperties`. `SDL_Vulkan_LoadLibrary` dlsyms
those **by name** out of whatever library it is pointed at, and `window.cpp` points it at
`libcw_runtime.so` — one loader for SDL's surface and for the renderer, because two loaders means
two drivers and a surface on a different one from the swapchain.

### 5.3 Turnip, BCn and what the renderer actually requires

`vk_renderer.cpp` refuses a device that does not have **Vulkan 1.3**, `bufferDeviceAddress`,
`descriptorIndexing` + `runtimeDescriptorArray`, `dynamicRendering`, `shaderInt64`,
`independentBlend` and **`textureCompressionBC`** — each with a `CW_FEAT` line naming the feature
and why. Consequences for a phone, in order of how often they bite:

* **Android 12 and below cannot reach 1.3 from the platform loader.** A Mesa Turnip build that
  reports 1.3 can, which is why the launcher's driver row is not an enthusiast extra on this title.
  The manifest declares `android.hardware.vulkan.version` at **1.1** and required, not 1.3, on
  purpose: the version the *platform* reports is not the version the *driver in use* reports, and
  declaring 1.3 would hide the app in store filtering from exactly the devices a custom driver
  rescues. The honest gate is the one the launcher runs and explains.
* **BCn is a hard requirement, not a nice-to-have.** The title uploads every texture as BC. Mesa's
  Turnip advertises `textureCompressionBC` on Adreno, so Turnip is the answer. For *stock* Qualcomm
  drivers adrenotools ships a patcher (`adrenotools_get_bcn_type` / `adrenotools_patch_bcn`) for
  hardware that decodes BC while the driver does not advertise it, and `EnableBcnIfPatchable` calls
  it before device creation.
* **The patcher patches format properties, not the features struct** — so on a driver that is
  PATCHABLE and still reports `textureCompressionBC = false` in
  `VkPhysicalDeviceFeatures`, the renderer's `CW_FEAT` check still refuses. Lifting that bit was
  considered and rejected: the drivers in that state are the pre-1.3 ones, which the API-version
  check already refuses, so the lift would buy nothing while claiming a feature we did not verify.
  The log says which of the three BCn states the driver is in (`INCOMPATIBLE` / `already supported`
  / `PATCHABLE`), so the refusal is diagnosable.

### 5.4 DXC, and the two honest ways to have shaders

The translator dlopens `libdxcompiler.so`. **There is no prebuilt arm64-Android libdxcompiler
anywhere this project can download** — Microsoft's releases ship x86/x64 Linux only, and
`renderbag/dxc-bin`'s arm64 artefacts are a macOS dylib and a Windows DLL. So:

1. **Ship a cache built on a desktop** — `tools/build_shader_spv.sh`, staged into the APK's assets
   by `build_apk.sh` when `CW_SHADER_SPV` points at one, and copied to
   `<root>/assets/shader_spv/` at first launch. This is the path a playable APK uses.
2. **Import a library somebody built** — the launcher has a row for it and exports `CW_DXC_LIB`.
   Note that `<nativeLibraryDir>/libdxcompiler.so` is already one of the translator's search
   candidates (`ExeDir()` *is* nativeLibraryDir on Android), so an APK that bundles one in
   `jniLibs` needs no variable at all.

With neither, the runtime boots, refuses each translation with one log line, and presents the black
screen the first-run check exists to prevent — which is why the launcher states the situation rather
than hiding the row.

**So option 2 is not really optional, and `tools/android/build_dxc.sh` exists because of that.**
The reasoning is in the runtime's own history: D.1 established that the disc's `.vo` objects are
*templates* the title patches at bind time, so 0 of 104 runtime vertex shaders exist verbatim on
disc. `tools/release/vs_recipes.bin` — **tracked in this repository**, 107 recipes, magic `ZCVR`,
generated by `tools/vs_recipes.py` from a census of a machine that has *run* the game — recovers 102
of them as template-plus-patch, and `gpu/shader_prebuild.cpp` applies them during the first-run pass
with a two-sided gate: every recipe must FNV-1a back to the runtime hash it claims, and every
vertex-shader hash the pre-warm seed names must be a recipe or one of two known synthesised shaders.
That leaves exactly two — `vs_539ea9e08aa83f0c` (108 B) and `vs_a4ae7c2b7c1818c4` (60 B) — which are
engine-synthesised, have no template anywhere, and are the 2nd and 4th shaders the title ever binds:
at boot, **before any visible frame**. They can only come from translate-on-first-sight, i.e. from
DXC on the device.

A residue of two shaders is therefore not a small gap. A phone with no DXC boots, translates nothing,
and presents black — because the two shaders every frame depends on are the two it cannot get. That
is the whole argument for `build_dxc.sh`, and it is why the file is a requirement rather than an
optimisation even when the cache is otherwise complete.

The script pins a release tag (`v1.9.2607`), clones shallow, initialises only the two submodules a
SPIR-V-only build reads, passes DXC's own `cmake/caches/PredefinedParams.cmake` with `-C` the way
DXC's docs prescribe, turns every test target off, caps link parallelism at 2 (LLVM's link is the
memory-hungry step, and an OOM-killed dxcompiler link reports as a compiler crash with no mention of
memory an hour into a build), and builds the `dxcompiler` target only. Then it verifies what it made
the way the other four scripts do: ELF machine against the ABI, and `DxcCreateInstance` present in
the dynamic symbol table — because a library that loads but does not export that one symbol makes
`LoadDxcOnce` skip the candidate and report "no dxcompiler library found" for a file that is plainly
there.

Two choices in it worth stating, since both look like defaults:

* **`ANDROID_STL=c++_shared`, not `c++_static`.** The runtime is built against `c++_shared` (AGP's
  default) and the APK ships one `libc++_shared.so`. A DXC statically linked against its own copy
  puts a second C++ runtime in one process, which is the classic route to two heaps and an abort
  inside somebody else's destructor.
* **The output goes to `<prefix>/jniLibs/`,** which Gradle packages into `lib/<abi>/`, which the
  framework extracts to `nativeLibraryDir` (`extractNativeLibs` is true anyway for adrenotools),
  which *is* `HostPaths::ExeDir()` on Android — and `ExeDir()/libdxcompiler.so` is already a search
  candidate. So the entire integration is the file being there: no `CW_DXC_LIB`, no launcher row, no
  code that knows about it. `app/build.gradle.kts` warns (not fails) when a *real* guest image is
  being packaged without it, because that combination boots to a black screen and the stub-image
  artifact CI builds on every pull request has nothing to translate.

## 6. Input: touch is the fourth source, not a mode

`runtime/cpu/touch_input.h` carries the argument; the summary is that this runtime already has three
input sources and one contract — whatever a device does becomes an XInput-shaped `HostPadState`
published to pad 0, with the packet number moving only when the state changes. A finger on glass is
a fourth device with the same output, so it takes the same path, and taking the same path is what
makes it correct without a second theory of input: the guest sees one pad, the title's own deadzones
and ramp apply, the input trace prints touch presses beside pad presses, and the PC-options panel
(which reads pad-0 polls *only*) works from a phone with no further work.

**Geometry is Java, decoding is shared, merging is native.** `TouchOverlayView.kt` owns which
controls exist, where they sit, how big they are, whether they are visible, and the edit mode. It
publishes one decoded snapshot per touch event in XInput units — including the Y flip, because SDL's
stick Y points down and XInput's points up. `touch_input.cpp` owns the merge and the guarantees.

Two rules that are easy to get wrong and are therefore written in both places:

* **The drift rule.** `window.cpp` zeroes a controller's sub-deadzone axes when the keyboard
  contributes, because an idle pad reporting 18% deflection otherwise carries its drift into every
  key press. A touch stick has the same problem with a Bluetooth pad, so the same rule applies —
  and it is applied to the *controller-only* state **before** the touch values merge in. Zeroing
  afterwards would eat the touch stick's own deflection: unlike the keyboard's full-scale ±32767, a
  thumb publishes proportional values, and a gentle walk is exactly the sub-deadzone range the rule
  discards. This is why the merge call sits *between* `ReadController()` and the keyboard read in
  the event loop, and why the keyboard's own zeroing is guarded with `kbActive && !touchActive`.
* **Nothing may stay stuck.** The title polls a state and never sees an event, so a finger holding
  RT when the player took a phone call is still holding RT when they come back. `TouchInput_Clear()`
  is called on pause, on hide, on switch-off and when the overlay's last finger lifts, and a
  snapshot whose finger count disagrees with its contents is counted as a rejected leak — which is
  what turns "my character walks left forever" from a debugging session into a log line.

**Touches that miss a control are not consumed.** `onTouchEvent` returns false and they fall through
to SDL's surface, so the title still gets the taps and drags it would have got without an overlay —
menus included. An overlay that swallowed the screen would be an overlay that had to reimplement
every menu the game has. This is also why entering edit mode is a *button* and not a long-press on
the overlay: recognising a long-press on empty screen means claiming touches the game was owed.

The player-facing switch is `CW_NO_TOUCH` for the boot-time decision and
`TouchInput_SetEnabled` for the in-session one, deliberately not writing the settings file: a
mid-session change that survives a relaunch is a surprise.

## 7. Performance

The governor in `android_perf.cpp` is one mechanism with two arms and a ladder, initialised after
`Settings_Load` so the persisted internal resolution is its ceiling.

* **The ladder** is every *valid* internal resolution from the ceiling down to the renderer's 720-high
  floor, each a factor of the height, each checked with `Settings_ValidInternalRes` once at init.
  A rung that fails validation is not a rung.
* **The frame-rate arm** moves down after 3 consecutive samples under 85% of target and up after 10
  over 105%. Asymmetric on purpose: dropping is cheap and a stutter is visible, climbing is free and
  a resolution that oscillates is worse than either. A sample of 0 fps is a backgrounded app, not a
  slow one, and is discarded — scaling down for a phone in somebody's pocket is how a governor earns
  a reputation for ruining the next session.
* **The thermal arm** caps the ladder at rung 1 on `THERMAL_STATUS_MODERATE` and at the floor on
  `SEVERATE` and above, fed from `PowerManager` (API 29+) through `nativeSetThermalStatus`.
* **The target** is the player's frame-rate cap when set and the console's own 30 otherwise: this
  title ran at 30 on hardware, and a phone with no cap set has no business being governed towards an
  uncapped frame rate it cannot reach.

It disables itself, loudly, when `CW_VK_RES` or `CW_VK_RES_SCALE` pins the resolution: an
auto-scaler inside a resolution experiment measures the scaler. That is why the launcher's
resolution row says what picking a fixed value costs.

**Off-switch spelling.** `CW_ANDROID_AUTO_SCALE` / `CW_ANDROID_THERMAL` are *feature*-named, so `0`
is the off spelling and unset means on. `CW_NO_TOUCH` / `CW_VK_NO_CUSTOM_DRIVER` are *disable*-named,
so any value but `0` turns the thing off. Applying the second idiom to the first kind of name
inverts a control arm silently, and that exact bug shipped in this file once: `EnvSaysOff` returned
true for `"1"`, so `CW_ANDROID_AUTO_SCALE=1` disabled the governor while `=0` enabled it — the
inverse of the sentence printed at init. Both spellings are documented at `EnvSaysOff` because the
only way to catch it is to read the log and notice the state disagreeing with the variable.

**Big-core pinning** (`CW_ANDROID_PIN_BIG`, off by default) pins the pump thread to the performance
cluster, found via `cpu_capacity` in sysfs with `cpuinfo_max_freq` as the fallback. It is a trade
rather than a win — it stops a mid-frame migration onto a little core and it makes that thread
compete with the render thread for one cluster — and it is **owed a measurement** (§11, A6).

## 8. Paths, memory, and the platform's small refusals

* **`CW_ROOT`**, set by the launcher to `<filesDir>/cw`, is the data root and the only one.
  `host_paths.cpp`'s executable walk cannot work: `/proc/self/exe` is the framework's
  `app_process64` in a read-only directory holding nothing of ours. `QueryExePath` uses `dladdr` on
  its own function to answer "where am I installed" (nativeLibraryDir), and refuses to guess a data
  root — with `CW_ROOT` unset it prints the variable's name rather than silently resolving assets
  against a read-only tree.
* **`ExeDir()` is nativeLibraryDir**, which holds `.so` files and nothing else. Three assets are
  looked up beside the executable first and already had a `<root>/tools/release/` fallback
  (`VsRecipes()`, `FindChipsDir()`); `prewarm.keys` did not, so `vk_renderer.cpp` gained an
  Android-only second candidate. Scoped to Android rather than made general: a *desktop dev tree*
  also has no `prewarm.keys` beside its executable, and a general fallback would quietly hand every
  dev run the checked-in seed — 1,365 speculative pipeline builds, which is why that loop is async.
* **`memfd_create`** has a libc wrapper in Bionic only from API 30 while the syscall is there from
  Linux 3.17, so on an Android 8–10 phone the ordinary call is a *compile* error naming a function
  that plainly exists. `kernel/memory.cpp` calls the syscall directly through a declaration we
  supply. Same behaviour, same flags.
* **`extractNativeLibs` must be true** (`packaging.jniLibs.useLegacyPackaging`): adrenotools dlopens
  its hook libraries by name out of `nativeLibraryDir`, which only holds real files when the
  libraries were extracted at install. With the modern default they stay compressed inside the APK
  and the custom driver load fails on a device whose driver support is otherwise fine.
* **`-msse4.1 -mavx` are gated off AArch64** in `ppc_image`. Not merely unnecessary — clang rejects
  them for an arm64 target outright, so an ungated list stops the build at the first of 228
  generated TUs with a message about a flag and nothing about a platform. NEON is baseline, not an
  opt-in, so there is no arm64 equivalent to add.
* **No `DT_RUNPATH`** (`CW_BUNDLE_RPATH` off): bionic ignores it, and `.dynstr` sits *before*
  `.text`, so dead bytes there relocate the image and quietly break a release `.text`-identity
  comparison.
* **No `CW_SPLIT_DEBUG`**: Gradle strips with the NDK's own `llvm-strip` at packaging and keeps the
  unstripped copy under `intermediates/merged_native_libs`, which is where a symbolication run
  against a phone crash has to look anyway.

## 9. XLive is not in this build

`-DCW_XLIVE=OFF` drops the five `kernel/xlive_*.cpp` sources and compiles
`kernel/xlive_stub.cpp` instead — generated from the five headers by `tools/gen_xlive_stub.py`,
signatures copied verbatim and the headers included, so a drifted declaration is a compile error in
the stub rather than an undefined symbol in somebody's link. 41 functions; each returns the OFF
answer for its type, and the two that take a `fallback` return it, because
`xlive_glue.h` documents "the account's XUID and gamertag, or the fallback when signed out" and
inventing a different XUID would be worse than having none.

**This is a build-time state and not a runtime one**, and the difference matters: `CW_NO_XLIVE=1`
reaches the real implementation and asks it to behave as if signed out, which is what every
measurement of the offline path was taken against. A `CW_XLIVE=OFF` build never had that
implementation. The stub says so out loud at `CwXlive_Start`, because a build that quietly has no
achievements is a build whose bug reports will be about achievements.

Two builds need it and in both the checkout cannot exist: CI (XenonLive is private, so no runner can
clone it — without this option `build.yml` failed at configure and proved nothing) and Android
(milestone **A0**: achievements and co-op sessions are not what makes the title playable on a phone,
and libxlive's dependency closure is one more thing to cross-compile before a first frame).

## 10. Building it

```bash
tools/android/build_apk.sh                 # everything, from the dependencies up (stub image)
tools/android/build_apk.sh --skip-deps     # one Gradle invocation, dependencies already built

# A PLAYABLE one. All three inputs are needed and each is missing for a different reason:
CW_PPC_DIR=$PWD/ppc \                     # the real guest image (from your XEX, on a machine
                                           #   that has it — no runner may hold one)
CW_SHADER_SPV=$PWD/assets/shader_spv \    # the cache, if you have one; skips the pixel-half
                                           #   translation on the phone at first run
  tools/android/build_apk.sh --with-dxc    # and the compiler itself: an LLVM build, because no
                                           #   prebuilt arm64 libdxcompiler.so exists (§5.4)

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -s CaseWest                     # [shxlate] dxcompiler: ... is the line that says
                                           #   DXC loaded, and from where
```

`build_apk.sh` is the ordering of the steps with the checks between them, so a failure names the
step rather than arriving as a link error 228 TUs in. It stages `tools/release/{prewarm.keys,
vs_recipes.bin,kbm_chips}` into the APK's assets, generates the stub image when there is no real
one, runs both generated-source gates, and then verifies the artifact: the right ABI, `libc++_shared.so`
present, all four adrenotools hooks present when adrenotools is in the build, and
`extractNativeLibs` true.

### 10.1 The one build CI can make playable

`.github/workflows/android.yml` has a third job, `playable`, which runs **only** on an explicit
dispatch and is the only job in this repository that is handed a copy of the game. The reason the
other two cannot be: no runner may hold copyrighted game data as a matter of course, so they build
`tools/gen_stub_ppc.py`'s stub and prove compile / link / package / boot. A dispatch is different in
kind — one person, their own link, at a moment they chose — which is the line between a repository
that distributes a game and a repository whose owner built their own copy on rented hardware.

It takes the URL as `xex_url` or, preferably, as the `CW_XEX_URL` repository secret, and the
difference matters more than it looks: **this repository is public, and a `workflow_dispatch` input
is recorded on the run page, which is public.** `add-mask` keeps the value out of the log; it cannot
retract what the form already published. The job says so in a warning when the input was used.

It accepts either a bare `default.xex` (magic `XEX2`) or the XContent package (magic `CON\0`/`LIVE`/
`PIRS`), which it unpacks with `tools/extract_stfs.py`, and it names the third case explicitly: a URL
that serves a web page rather than bytes, which is what a Drive/Dropbox share link does and which
`curl -fL` saves without complaint. Otherwise that error arrives forty minutes later as
"XenonRecomp produced nothing".

Then it builds the **host** XenonRecomp, applies `tools/ci/xenonrecomp-local.patch`, and generates
`ppc/` from `config/` — where the committed `CaseWest.toml` resolves `../assets/game/default.xex`
into `../ppc`, with the committed switch tables. The patch is load-bearing here in a way it is
nowhere else: `CaseWest.toml` records that this title's XEX uses the **devkit all-zero key**, that
stock XenonRecomp hardcodes the retail one, and that the wrong key yields an empty `Image` **with no
diagnostic at all**. So the job counts the mapping table afterwards and fails under 40,000 entries
against a documented 58,448 — a second of work that converts the quietest failure in the pipeline
into a named one.

**Shaders are two steps, and they are the difference between an APK that boots and one that draws.**
§5.4 is the argument; the mechanics here are that the job builds the *cache* on the runner with the
x86_64 DXC XenosRecomp already vendors, and cross-compiles DXC *itself* for arm64 so the phone can
translate what the cache does not hold. The cache step is cheap in a way that looks like a trick and
is not: SPIR-V is architecture-independent and the cache key is the hash of the **microcode**, not of
the compiler, so what an x86_64 runner writes is exactly what an arm64 device looks up. It runs the
host runtime's own `--build-shader-cache` CLI branch against the package's
`data/shaders/deadrisingepilogue-ps.big`, and that branch returns before the guest exists — so the
host binary is linked against the *stub* image and the pass still produces a real cache. Linking the
58k-function image there would cost an hour to build a binary that never calls into it.

The vertex half **is** covered, because `tools/release/vs_recipes.bin` is tracked here (§5.4) and
`CW_ROOT` in that step is what lets `HostPaths::VsRecipes()` find it — without the variable the pass
would do the pixel half only, report success, and be missing 104 shaders, which is worse than
failing. So the cache the job produces is ~1,367 of ~1,369 modules, and the two that remain are the
engine-synthesised pair bound before the first frame. The job prints the count and names the residue
rather than leaving either to inference, because "the cache built" is not the same claim as "the
cache is complete", and only the second one draws.

The APK is uploaded only when the dispatch's `artifact` choice says so, with `retention-days: 1`, and
the choice's own label states the consequence — game-derived code in an artifact on a public
repository is a public download for as long as it is retained, and no workflow can make that private.
The default is logs only.

See `android/README.md` for the Gradle-level view and the properties that override the defaults.

## 11. Milestones, and what has actually been proven

| | | Status |
|---|---|---|
| **A0** | The build: one source list, shared library on Android, four new files compiled everywhere, no linked libvulkan, XLive off, `-msse4.1 -mavx` gated | implemented; proven by `.github/workflows/android.yml`'s `apk` job |
| **A1** | Paths and memory: `CW_ROOT`, `dladdr` exe dir, direct `memfd_create` syscall, `tools/release` fallbacks | implemented; **not yet run on a device** |
| **A2** | The bridge: `SDL_main`, logcat mirror, JNI callbacks, env contract and its boot-time dump | implemented; proven only by the emulator gate below |
| **A3** | The GPU: shadow table, two-phase fill, adrenotools loader choice, BCn patch, SDL's five exported symbols | implemented; **never run against a real Adreno driver** |
| **A4** | Input: the overlay, the merge, the drift rule, the stuck-button guarantees, the counters | implemented; desktop compile-checked, **no device** |
| **A5** | The app: launcher, driver import, first-run progress UI, thermal, rumble, touch settings | implemented; **no device** |
| **A6** | Performance: the ladder, both arms, the pin | implemented; **OWED A MEASUREMENT** — the pin's effect and the ladder's step factor are both unmeasured on real hardware, and `CW_ANDROID_PIN_BIG` is off by default until somebody measures it |

What the CI proves, and it is scoped the same way `build.yml` scopes itself: the sources compile for
arm64, the runtime links as a shared library, an APK packages with the right contents, the generated
stubs are current, and — on a push to master — an x86_64 emulator installs it, `GameActivity` starts
with `--esa cwArguments --smoke`, and logcat contains the harness's own `OK:` line plus `CW_ROOT=`.
That last gate is the only one that proves anything about *running*, and it is x86_64 because that is
the only ABI an emulator on a hosted x86_64 runner can execute. It carries `-msse4.1 -mavx` (the
gate is on `CMAKE_SYSTEM_PROCESSOR`, not on Android), which the emulator's `android64` CPU model
provides; a runner without AVX would SIGILL, and the log would say so.

**No runner may hold the game**, so CI's APK carries the stub image: it boots, reports a missing
package, and exercises every Android code path this port added. It does not play Dead Rising.

What is *not* proven by anything yet, in the order it will hurt:

1. That a real Adreno driver — Turnip or stock — creates a device against this renderer's `CW_FEAT`
   list. Everything in §5.3 is reasoning from headers and from what other emulators do. The same
   doubt covers `tools/android/build_dxc.sh`: DXC cross-compiles for Android (people build it with
   the NDK), but no run of that script has produced a library yet, and LLVM's cross-configure is
   where such a script fails — host TableGen, submodule depth, a linker flag.
2. That the touch overlay is *usable*. The mapping is checkable; a control that is 66 dp on a 6.7"
   panel and 66 dp on a tablet is a claim about thumbs that nobody has made with a thumb.
3. That the governor's numbers (0.85 per rung, 3 samples down, 10 up) are right for a phone rather
   than merely right for the reasoning that produced them.
4. Anything about audio: `audio_out.cpp` goes through SDL, which on Android means OpenSL or AAudio,
   and neither has been heard.
