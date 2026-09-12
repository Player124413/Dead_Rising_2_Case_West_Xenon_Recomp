#!/usr/bin/env bash
# Build the Android APK, from the four cross-compiled dependencies up.
#
# WHAT THIS SCRIPT IS FOR. `gradle assembleDebug` is one command, and it does not work on a
# clean machine: the runtime links a static SDL2, a static LGPL ffmpeg, three static libraries
# out of a sibling XenonRecomp checkout and optionally libadrenotools, none of which Gradle can
# fetch, and all four of which have to be built for the same ABI by the same NDK that compiles
# the runtime. This is the ordering of those steps with the checks between them, so that a
# failure names the step that failed instead of arriving as a link error 228 translation units
# into a build.
#
# WHAT A GREEN RUN MEANS, and it is scoped the same way .github/workflows/build.yml scopes
# itself. Without the game there is no real recompiled image, so the APK this produces on CI is
# built from tools/gen_stub_ppc.py's stub: it installs, it boots, it reports a missing package
# and it exercises every Android code path this port added. It does NOT play Dead Rising. An
# APK that plays is the same command with CW_PPC_DIR pointing at a ppc/ tree generated from the
# title's XEX on a machine that has it:
#
#     CW_PPC_DIR=$PWD/ppc tools/android/build_apk.sh
#
# Usage:
#   tools/android/build_apk.sh [--release] [--skip-deps] [--stub-ppc] [--with-dxc] [--abi ABI]
#
#   --release     assembleRelease instead of assembleDebug (signed with cw.keystore if set,
#                 otherwise with the debug key — see android/app/build.gradle.kts)
#   --skip-deps   do not build the dependencies; use whatever is already in third_party/
#   --stub-ppc    force the stub image even when a real ppc/ tree exists
#   --with-dxc    also build libdxcompiler.so for this ABI (tools/android/build_dxc.sh). It is an
#                 LLVM build and is NOT built by default: a stub-image APK has no shaders to
#                 translate. A PLAYABLE one cannot draw without it — see build_dxc.sh's header.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

VARIANT=debug
SKIP_DEPS=0
FORCE_STUB=0
WITH_DXC=0
ABI=${CW_ABI:-arm64-v8a}
while [ $# -gt 0 ]; do
    case "$1" in
        --release)   VARIANT=release ;;
        --skip-deps) SKIP_DEPS=1 ;;
        --stub-ppc)  FORCE_STUB=1 ;;
        --with-dxc)  WITH_DXC=1 ;;
        --abi)       shift; ABI=${1:?--abi needs a value} ;;
        -h|--help)   sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "FAIL: unknown argument $1 (try --help)" >&2; exit 1 ;;
    esac
    shift
done

PREBUILT="$ROOT/third_party/android/$ABI"
SDL2_PREFIX="$PREBUILT/sdl2"
FFMPEG_PREFIX="$PREBUILT/ffmpeg"
ADRENO_PREFIX="$PREBUILT/adrenotools"
DXC_PREFIX="$PREBUILT/dxc"
SDL_JAVA="$ROOT/third_party/android/sdl-java"
APP_ASSETS="$ROOT/third_party/android/app-assets"

XENON_ROOT=${CW_XENON_ROOT:-$HOME/GithubRepo/XenonRecomp}
XENOS_ROOT=${CW_XENOS_ROOT:-$HOME/GithubRepo/XenosRecomp}
XENON_BUILD="$XENON_ROOT/build-android-$ABI"

echo "======================================================================"
echo " Case West Android — $ABI, $VARIANT"
echo "======================================================================"

# ---------------------------------------------------------------------------------------
# 0. The tools, checked before anything is built.
#
# A missing tool discovered after a 20-minute ffmpeg build is a 20-minute waste, and the
# failure it produces (gradle: command not found) does not say which of the four prerequisites
# was actually missing.
# ---------------------------------------------------------------------------------------
echo "==> checking the build tools"
fail_tools=0
need() { command -v "$1" >/dev/null 2>&1 || { echo "    MISSING $1 — $2" >&2; fail_tools=1; }; }
need cmake  "the runtime and all four dependencies are CMake projects"
need ninja  "every build here uses -G Ninja"
need python3 "tools/gen_stub_ppc.py and the two generated-stub gates"
need git    "the sibling checkouts and libadrenotools are cloned"
need curl   "SDL2 and ffmpeg are fetched as release tarballs"

# Gradle: the wrapper if somebody generated it, else a gradle on PATH (which is what
# gradle/actions/setup-gradle provides on CI). Neither is an error yet — but both missing is.
GRADLE=""
if [ -x "$ROOT/android/gradlew" ]; then
    GRADLE="$ROOT/android/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE=gradle
else
    echo "    MISSING gradle — no android/gradlew and no gradle on PATH." >&2
    echo "      Install Gradle 8.9+ or run \`gradle wrapper\` once inside android/." >&2
    fail_tools=1
fi

# Java 17: AGP 8.7 requires it, and an older JDK fails inside Gradle with a message about
# bytecode versions rather than one about the JDK.
if command -v java >/dev/null 2>&1; then
    JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    echo "    java              ${JAVA_MAJOR:-unknown}"
    if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 17 ]; then
        echo "    Java $JAVA_MAJOR is too old for AGP 8.7 — install a JDK 17." >&2
        fail_tools=1
    fi
else
    echo "    MISSING java — AGP 8.7 needs a JDK 17." >&2
    fail_tools=1
fi
[ "$fail_tools" -eq 0 ] || { echo "FAIL: build tools missing (above)." >&2; exit 1; }

cw_require_ndk "$ABI"
echo "    gradle            $GRADLE"

# ---------------------------------------------------------------------------------------
# 1. The four dependencies.
#
# Each is built only when its output is missing, so a re-run after a runtime edit costs one
# Gradle invocation and not an hour of cross-compiling. --skip-deps says "I know what is in
# third_party/" and turns a missing dependency into an error rather than a build.
# ---------------------------------------------------------------------------------------
if [ "$SKIP_DEPS" -eq 0 ]; then
    if [ ! -f "$SDL2_PREFIX/lib/libSDL2.a" ] && [ ! -f "$SDL2_PREFIX/lib64/libSDL2.a" ]; then
        echo; echo "==> SDL2 (static, $ABI)"
        CW_ABI="$ABI" "$HERE/build_sdl2_android.sh" "$SDL2_PREFIX" "$SDL_JAVA"
    else
        echo "==> SDL2 present: $SDL2_PREFIX"
    fi

    if [ ! -f "$FFMPEG_PREFIX/lib/libavcodec.a" ]; then
        echo; echo "==> ffmpeg (static, LGPL, xma1+xma2)"
        CW_ABI="$ABI" "$HERE/build_ffmpeg_android.sh" "$FFMPEG_PREFIX"
    else
        echo "==> ffmpeg present: $FFMPEG_PREFIX"
    fi

    if [ ! -f "$XENON_BUILD/XenonUtils/libXenonUtils.a" ]; then
        echo; echo "==> XenonRecomp static libraries (arm64)"
        CW_ABI="$ABI" "$HERE/build_xenon_android.sh" "$XENON_ROOT" "$XENON_BUILD"
    else
        echo "==> XenonRecomp arm64 libraries present: $XENON_BUILD"
    fi

    if [ ! -f "$ADRENO_PREFIX/lib/libadrenotools.a" ]; then
        echo; echo "==> libadrenotools (custom GPU drivers)"
        # A failure here is a WARNING and not a stop: the app builds and runs without it, on
        # the device's own driver, and says so in the launcher. Blocking an APK because an
        # optional dependency did not build would be a build system with priorities the port
        # does not have.
        CW_ABI="$ABI" "$HERE/build_adrenotools.sh" "$ADRENO_PREFIX" \
            || echo "    WARNING: adrenotools did not build — continuing WITHOUT custom driver support"
    else
        echo "==> adrenotools present: $ADRENO_PREFIX"
    fi
    if [ "$WITH_DXC" -eq 1 ]; then
        if [ ! -f "$DXC_PREFIX/jniLibs/libdxcompiler.so" ]; then
            echo; echo "==> DXC (libdxcompiler.so, $ABI) — an LLVM build, the long one"
            # A failure here IS a stop, unlike adrenotools': the caller asked for it by name with
            # --with-dxc, and the APK it produces without it boots to a black screen with one
            # easily-missed log line. Silent degradation was the whole problem.
            CW_ABI="$ABI" "$HERE/build_dxc.sh" "$DXC_PREFIX"
        else
            echo "==> DXC present: $DXC_PREFIX/jniLibs/libdxcompiler.so ($(cat "$DXC_PREFIX/version.txt" 2>/dev/null || echo '?'))"
        fi
    fi
else
    echo "==> --skip-deps: using whatever is in $PREBUILT"
fi

# The three that are not optional, asserted here rather than left to Gradle's configuration
# error, because Gradle's arrives after the NDK has been resolved and the sources scanned.
for f in "$SDL_JAVA/org/libsdl/app/SDLActivity.java" \
         "$FFMPEG_PREFIX/lib/libavcodec.a" \
         "$XENON_BUILD/XenonUtils/libXenonUtils.a"; do
    [ -e "$f" ] || { echo "FAIL: missing $f (re-run without --skip-deps)" >&2; exit 1; }
done
if [ ! -d "$XENOS_ROOT/XenosRecomp" ]; then
    cat >&2 <<EOF
FAIL: no XenosRecomp checkout at $XENOS_ROOT.

  git clone https://github.com/hedge-dev/XenosRecomp $XENOS_ROOT
  git -C $XENOS_ROOT submodule update --init thirdparty/dxc-bin

The shader translator is compiled INTO the runtime from that checkout (release D.2), so it is
a source dependency on every platform and not something an Android build can skip.
EOF
    exit 1
fi

# ---------------------------------------------------------------------------------------
# 2. The runtime's own assets, staged where Gradle packages them.
#
# These are ours, not the game's: prewarm.keys (the pipeline keys the boot warms from),
# vs_recipes.bin (the vertex recipes that let session one hold every shader) and the 26 kbm
# chip blobs (the key-cap prompt art, generated by tools/gen_kbm_icons.py). They are checked
# into tools/release/ and copied here because an APK's assets cannot be a symlink to a
# directory outside the module.
# ---------------------------------------------------------------------------------------
echo "==> staging the runtime's assets"
rm -rf "$APP_ASSETS/cw"
mkdir -p "$APP_ASSETS/cw"
staged=0
for f in prewarm.keys vs_recipes.bin; do
    if [ -f "$ROOT/tools/release/$f" ]; then
        cp "$ROOT/tools/release/$f" "$APP_ASSETS/cw/$f"
        staged=$((staged + 1))
    else
        echo "    WARNING: no tools/release/$f — the runtime will report it missing at boot" >&2
    fi
done
if [ -d "$ROOT/tools/release/kbm_chips" ]; then
    mkdir -p "$APP_ASSETS/cw/kbm_chips"
    cp "$ROOT"/tools/release/kbm_chips/*.dxt "$APP_ASSETS/cw/kbm_chips/" 2>/dev/null || true
    chips=$(find "$APP_ASSETS/cw/kbm_chips" -name '*.dxt' | wc -l)
    # 26 is the count tools/release_package_linux.sh asserts, and for the same reason: the
    # overlay generator refuses a bank entry with no matching chip, and "the prompt icons are
    # blank" is a defect that reads as an art problem.
    [ "$chips" -eq 26 ] || echo "    WARNING: $chips of 26 kbm chip blobs staged" >&2
    staged=$((staged + chips))
fi
# A shader cache built on a desktop, if the person building this APK had the game. Never
# present in CI (no runner may hold the game), and its absence is not a build failure: it is a
# first-run that has to translate, which needs DXC. See docs/android-port-plan.md §5.
if [ -n "${CW_SHADER_SPV:-}" ] && [ -d "$CW_SHADER_SPV" ]; then
    mkdir -p "$APP_ASSETS/shader_spv"
    cp -r "$CW_SHADER_SPV"/. "$APP_ASSETS/shader_spv/"
    echo "    shader cache      $(find "$APP_ASSETS/shader_spv" -type f | wc -l) file(s) from $CW_SHADER_SPV"
fi
echo "    staged            $staged file(s) -> $APP_ASSETS"

# ---------------------------------------------------------------------------------------
# 3. The guest image: the real one if there is one, the stub if there is not.
# ---------------------------------------------------------------------------------------
PPC_DIR_ARG=""
# Set in BOTH branches rather than inferred from PPC_DIR_ARG at report time. The first version of
# this printed `${PPC_DIR_ARG:+stub}${PPC_DIR_ARG:-real}`, which is "stubreal" for a real image and
# "real" for the stub — the exact inversion of the one fact a person installing this APK needs, on
# the one line they will read. A label that lies about the artifact is worse than no label.
PPC_KIND=""
if [ "$FORCE_STUB" -eq 1 ] || [ ! -f "${CW_PPC_DIR:-$ROOT/ppc}/ppc_func_mapping.cpp" ]; then
    PPC_KIND="STUB — installs and boots, does NOT play the game"
    STUB="$ROOT/third_party/android/stub-ppc"
    echo "==> generating the STUB guest image"
    cat >&2 <<'EOF'
    No generated code was found, so this APK carries tools/gen_stub_ppc.py's stub image:
    it installs, boots, reports a missing package and exercises every Android code path,
    and it does NOT play the game. That is the honest scope of a CI artifact — no runner may
    hold the game — and it is the same scope .github/workflows/build.yml gives itself.

    For an APK that plays, generate the real image on a machine that has the XEX and point
    this script at it:
        CW_PPC_DIR=$PWD/ppc tools/android/build_apk.sh
EOF
    python3 "$ROOT/tools/gen_stub_ppc.py" --out "$STUB" --xenon "$XENON_ROOT"
    PPC_DIR_ARG="-Pcw.ppcDir=$STUB"
else
    echo "==> guest image: ${CW_PPC_DIR:-$ROOT/ppc} (the real recompiled tree)"
    PPC_KIND="real — ${CW_PPC_DIR:-$ROOT/ppc}"
    PPC_DIR_ARG="-Pcw.ppcDir=${CW_PPC_DIR:-$ROOT/ppc}"
fi

# ---------------------------------------------------------------------------------------
# 4. The two generated-source gates, run before the build rather than after a link failure.
# ---------------------------------------------------------------------------------------
echo "==> checking the generated sources are current"
python3 "$ROOT/tools/gen_xlive_stub.py" --check
python3 "$ROOT/tools/gen_vk_shadow.py" --check

# ---------------------------------------------------------------------------------------
# 5. Gradle.
# ---------------------------------------------------------------------------------------
# Capitalised by hand rather than with ${VARIANT^}: that expansion is bash 4, and macOS ships
# bash 3.2, where it is a syntax error — the kind of portability defect that only ever appears
# on the one machine that is not CI.
TASK="assemble$(printf '%s' "$VARIANT" | cut -c1 | tr '[:lower:]' '[:upper:]')$(printf '%s' "$VARIANT" | cut -c2-)"
echo "==> assembling the $VARIANT APK ($TASK)"
cd "$ROOT/android"
# shellcheck disable=SC2086
"$GRADLE" --no-daemon --stacktrace \
    "-Pcw.abi=$ABI" \
    "-Pcw.prebuiltRoot=$PREBUILT" \
    "-Pcw.sdl2Prefix=$SDL2_PREFIX" \
    "-Pcw.sdlJavaDir=$SDL_JAVA" \
    "-Pcw.ffmpegPrefix=$FFMPEG_PREFIX" \
    "-Pcw.adrenotoolsPrefix=$ADRENO_PREFIX" \
    "-Pcw.adrenotoolsJniLibs=$ADRENO_PREFIX/jniLibs" \
    "-Pcw.appAssets=$APP_ASSETS" \
    "-Pcw.xenonRoot=$XENON_ROOT" \
    "-Pcw.xenosRoot=$XENOS_ROOT" \
    "-Pcw.xenonBuild=$XENON_BUILD" \
    $PPC_DIR_ARG \
    "$TASK"

APK=$(find "$ROOT/android/app/build/outputs/apk/$VARIANT" -name '*.apk' | head -1)
[ -n "$APK" ] || { echo "FAIL: Gradle succeeded but produced no APK" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
# 6. What is actually in the artifact.
#
# Checked rather than assumed, because every one of these has failed in a way that produces a
# perfectly good-looking APK: a library for the wrong ABI, an APK with the hooks missing
# (custom drivers fail at selection time, not at build time), and — the one that motivated this
# block — extractNativeLibs=false, which is the modern default and which quietly breaks
# adrenotools' requirement that its hook libraries be real files in nativeLibraryDir.
# ---------------------------------------------------------------------------------------
echo "==> verifying $APK"
LIST=$(unzip -l "$APK")

check_in_apk() {
    if printf '%s\n' "$LIST" | grep -q "$1"; then
        echo "    present           $1"
    else
        echo "FAIL: $APK does not contain $1 — $2" >&2
        exit 1
    fi
}

check_in_apk "lib/$ABI/libcw_runtime.so" \
    "the runtime is not in the APK for this ABI. This port builds arm64-v8a only."
check_in_apk "lib/$ABI/libc++_shared.so" \
    "the C++ runtime is missing, and ANDROID_STL did not take."

if [ -f "$ADRENO_PREFIX/lib/libadrenotools.a" ]; then
    for h in libmain_hook.so libhook_impl.so libfile_redirect_hook.so libgsl_alloc_hook.so; do
        check_in_apk "lib/$ABI/$h" \
            "adrenotools dlopens this BY NAME from nativeLibraryDir; without it a custom driver fails at selection time, in a message from inside the driver loader."
    done
fi

# extractNativeLibs has to be true. Read from the binary manifest with aapt when the SDK has
# one, and otherwise from the packaging decision this script can see: useLegacyPackaging=true
# in app/build.gradle.kts is what makes the merger write it, so if that line has gone the
# answer has gone with it.
AAPT=$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}/build-tools" -name aapt2 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$AAPT" ]; then
    MANIFEST=$("$AAPT" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)
    if printf '%s' "$MANIFEST" | grep -q 'extractNativeLibs.*0xffffffff'; then
        echo "    extractNativeLibs true (0xffffffff)"
    elif printf '%s' "$MANIFEST" | grep -q 'extractNativeLibs'; then
        echo "FAIL: extractNativeLibs is not true in $APK." >&2
        echo "      adrenotools needs real files in nativeLibraryDir; packaging.jniLibs." >&2
        echo "      useLegacyPackaging must stay true in android/app/build.gradle.kts." >&2
        exit 1
    else
        echo "    extractNativeLibs not declared — checking the Gradle setting instead"
        grep -q 'useLegacyPackaging = true' "$ROOT/android/app/build.gradle.kts" \
            || { echo "FAIL: useLegacyPackaging is not true." >&2; exit 1; }
    fi
else
    grep -q 'useLegacyPackaging = true' "$ROOT/android/app/build.gradle.kts" \
        || { echo "FAIL: packaging.jniLibs.useLegacyPackaging is not true in app/build.gradle.kts." >&2; exit 1; }
    echo "    extractNativeLibs useLegacyPackaging=true (no aapt2 to read the manifest)"
fi

echo
echo "==> result"
printf '    %-22s %8s KB\n' "$(basename "$APK")" "$(( $(stat -c%s "$APK") / 1024 ))"
echo "    abi               $ABI"
echo "    variant           $VARIANT"
echo "    guest image       $PPC_KIND"
echo "    custom drivers    $([ -f "$ADRENO_PREFIX/lib/libadrenotools.a" ] && echo "adrenotools linked, hooks packaged" || echo "NOT in this build")"
if [ -f "$DXC_PREFIX/jniLibs/libdxcompiler.so" ]; then
    echo "    DXC               libdxcompiler.so packaged ($(( $(stat -c%s "$DXC_PREFIX/jniLibs/libdxcompiler.so") / 1048576 )) MB, $(cat "$DXC_PREFIX/version.txt" 2>/dev/null || echo '?'))"
    echo "                      shaders translate on the device; the cache builds itself at first run"
else
    echo "    DXC               NOT in this build"
    case "$PPC_KIND" in
        real*) echo "                      and the guest image is REAL: this APK boots and cannot draw."
               echo "                      tools/android/build_apk.sh --with-dxc" ;;
        *)     echo "                      (the stub image never reaches a draw, so this is expected here)" ;;
    esac
fi
echo "    path              $APK"
echo
echo "Install it with:"
echo "    adb install -r \"$APK\""
echo "and read what it does with:"
echo "    adb logcat -s CaseWest"
echo
echo "The smoke gate, on a device or an emulator:"
echo "    adb shell am start -n dev.casewest.android/.GameActivity --esa cwArguments --smoke"
