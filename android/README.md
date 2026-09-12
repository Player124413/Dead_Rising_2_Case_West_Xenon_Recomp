# android/ — the app

The Gradle half of the Android port. `docs/android-port-plan.md` is the plan of record and the
place the C++ points at when it needs to say *why*; this file is how to build and what the pieces
are.

```
android/
  settings.gradle.kts        one module, two repositories, no ranged plugin versions
  build.gradle.kts           AGP + Kotlin, pinned
  gradle.properties          every default: the ABI, the four dependency prefixes, the pins
  gradle/wrapper/            gradle-wrapper.properties only — the jar is not committed
  app/
    build.gradle.kts         the module: one ABI, one shared library, no dependencies
    src/main/AndroidManifest.xml
    src/main/java/dev/casewest/android/
      LauncherActivity.kt      the launcher: game file, driver, performance, touch, diagnostics
      GameActivity.kt          extends SDLActivity; the env export, the overlay, progress, rumble
      TouchSettingsActivity.kt per-control visibility and size, the master disable
      TouchOverlayView.kt      the controls: geometry, hit-testing, decoding, edit mode
      DriverManager.kt         importing Turnip / .adpkg driver packages
      RuntimeConfig.kt         every setting, as the environment the runtime reads
      GameFiles.kt             CW_ROOT: the layout, the seeding, the package import
      NativeBridge.kt          the whole JNI surface, in one file
      ProbeService.kt          asks which driver would load, in ITS OWN PROCESS
    src/main/res/              layouts, styles, a vector icon, the drop-zone drawable
```

Nothing outside `android/` and `tools/android/` is Android's, and nothing in here decides anything
about the runtime: flags, sources and feature switches are all in `../runtime/CMakeLists.txt`, the
same file the desktop build configures. `app/build.gradle.kts` tells it where the cross-compiled
dependencies are and packages what comes out.

## Building

```bash
# everything, from the four cross-compiled dependencies up
tools/android/build_apk.sh

# dependencies already built — one Gradle invocation
tools/android/build_apk.sh --skip-deps

# an APK that actually PLAYS: the real guest image, and the compiler that translates its
# shaders. --with-dxc is an LLVM build (there is no prebuilt arm64 DXC to download) and is
# not done by default because the stub-image APK has nothing to translate.
CW_PPC_DIR=$PWD/ppc tools/android/build_apk.sh --with-dxc

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -s CaseWest
```

Prerequisites: a JDK 17, Gradle 8.9+ (or `cd android && gradle wrapper` once to materialise
`gradlew`), CMake, Ninja, an Android SDK with the NDK pinned in `gradle.properties`, and `python3`.
`build_apk.sh` checks all of them before it builds anything, because a missing tool discovered after
a twenty-minute ffmpeg cross-build is a twenty-minute waste.

The dependencies — all four built by `tools/android/*.sh` into `third_party/android/<abi>/`, all
four gitignored:

| What | Why it is not fetched |
|---|---|
| static SDL2 + its `org/libsdl/app/*.java` | an APK cannot carry a second SDL, and the Java half version-checks the library |
| static ffmpeg, LGPL, xma1+xma2 only | the desktop release builds the same thing for the same licensing reason |
| libadrenotools + its four hook `.so` files | optional; without it there are no custom GPU drivers |
| `libdxcompiler.so` | not optional for a playable build, and not downloadable: `tools/android/build_dxc.sh` builds DXC from source with the NDK |
| arm64 XenonUtils / fmt / xxhash | the XEX loader is a static library and is not portable across architectures |

## The four decisions that look like mistakes and are not

**`getLibraries()` returns `{"cw_runtime"}`, not SDL's default `{"SDL2", "main"}`.** There is no
`libSDL2.so` and no `libmain.so` in this APK: SDL is linked statically into `libcw_runtime.so`, and
the runtime is a library rather than an executable because Android has no process to exec. That same
list decides which library `SDLMain` dlopens and calls `SDL_main` in — it takes the last entry and
wraps it as `lib<name>.so`, so it has to say `cw_runtime`, which is what `CW_ANDROID_SELF_LIB` in
`host/window.cpp` says with the platform's prefix attached and what CMake names the target.

**`loadLibraries()` is overridden to export the environment first.** SDLActivity calls it at the top
of `onCreate`, before anything else, and the runtime reads its configuration with `getenv` from
`SDL_main`. This is the last moment a variable can be written and still be seen; writing one later is
not late, it is invisible, because several are read once behind a `std::once_flag`. The data root is
seeded before the export for the same class of reason: `host_paths.cpp` resolves `CW_ROOT` with
`is_directory` and ignores a path that does not exist yet.

**`CMAKE_BUILD_TYPE=RelWithDebInfo` for BOTH variants, including debug.** A Debug build of the
recompiled image is not something anybody can wait for — Fable 2 measured one at roughly one movie
frame per *minute* — and it is not a measurement of anything, since every performance number this
project has recorded was taken at `-O2`. What the debug variant is for is a debuggable app with an
unstripped library, and `-O2 -g` delivers that.

**`useLegacyPackaging = true`.** adrenotools dlopens `libmain_hook.so` and `libhook_impl.so` by name
out of `nativeLibraryDir`, which holds real files only when the libraries were extracted at install.
With the modern default they stay compressed inside the APK, and the failure arrives when the player
picks a driver, in a message from inside the driver loader. `build_apk.sh` checks the packaged
manifest for it.

## No dependencies

`android.useAndroidX=false`, no Compose, no AppCompat, no Material Components, no Lifecycle. Four
Kotlin files, an `SDLActivity` subclass and the framework: `SharedPreferences`, `ContentResolver`,
`DocumentsContract`, `Vibrator`, `PowerManager`, `org.json`, `java.util.zip`. Every androidx artifact
added here is one more thing for CI to download and one more version that can move under a build, in
exchange for APIs the framework already has. SDL's own Java is framework-only too, so the dependency
list is exactly one entry: the Kotlin standard library.

## Overriding anything

Every default is a `-P` property; `gradle.properties` documents each one.

```bash
cd android
gradle assembleDebug -Pcw.abi=arm64-v8a -Pcw.ppcDir=$PWD/../../ppc \
    -Pcw.sdl2Prefix=/opt/android/sdl2 -Pcw.adrenotoolsPrefix=/opt/android/adrenotools
```

`cw.ppcDir` empty means the sibling `ppc/`, exactly as `runtime/CMakeLists.txt` reads it.
`cw.xenonRoot` / `cw.xenosRoot` empty mean `$HOME/GithubRepo/<name>` — expanded in
`app/build.gradle.kts` rather than in the properties file, because Gradle properties do not expand
environment variables and a literal `${HOME}` reaching CMake is a directory that does not exist.

### From CI, with your own XEX

`.github/workflows/android.yml` → **Run workflow** → paste a direct-download URL for `default.xex`
(or the STFS package) into `xex_url`, or set the `CW_XEX_URL` repository secret and leave the field
empty. It generates `ppc/` with the patched host XenonRecomp, verifies the generation was not the
silent empty-image failure the devkit key causes, and builds an APK whose guest image is the title's
own code. **Prefer the secret**: this repository is public and a dispatch input is recorded on the
public run page.

It builds the shader cache on the runner from the package's own banks — pixel shaders verbatim plus
102 of the 104 vertex shaders, via the tracked `tools/release/vs_recipes.bin` — and cross-compiles
DXC for arm64 (`tools/android/build_dxc.sh`, an LLVM build; there is no prebuilt one to download) for
the two that remain, which are engine-synthesised and bound before the first frame.
`docs/android-port-plan.md` §5.4 and §10.1 carry the argument, and the job's closing summary prints
the module count and names the residue rather than leaving either to inference.

A release build needs a keystore (`cw.keystore`, `cw.keystorePassword`, `cw.keyAlias`,
`cw.keyPassword`, never in git). Without one, `assembleRelease` signs with the debug key so the
artifact is still installable — this project's distribution is a file a player installs themselves,
and an unsigned APK cannot be installed at all.

## What the launcher asks a player for

1. **The game package.** The file an Xbox 360 downloaded: ~1.2 GB, no extension, in
   `Content/0000000000000000/58410B00/000D0000/`. Picking that file *or* the `58410B00` folder both
   work (the runtime's own search is recursive and size-based), and dragging either onto the drop
   zone works too. It is copied into the app's private storage, so uninstalling removes it — the row
   says so before the copy rather than after.
2. **A GPU driver**, optionally. See `docs/android-port-plan.md` §5.3 for why on this title it is
   often not optional: the renderer requires Vulkan 1.3 and BC textures, and a phone whose platform
   loader predates 1.3 cannot get there from its own driver.
3. **Nothing else.** Performance, touch layout and the rest have defaults that are the port's own
   answers rather than placeholders.
