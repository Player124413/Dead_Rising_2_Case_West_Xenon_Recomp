#!/usr/bin/env bash
# Build a STATIC SDL2 for Android, and copy the Java half that goes with it.
#
# WHY STATIC, AND WHY THIS SCRIPT COPIES .java FILES.
#
# An APK cannot carry a libSDL2.so that the framework did not put there without also
# shipping it, and shipping two copies of SDL in one APK means two answers to "which surface
# exists" — SDL's Android video driver owns exactly one, and window.cpp's first-run progress
# forwards to the app's own UI precisely because there is no second window to draw into. So
# SDL is linked INTO libcw_runtime.so and there is one shared library in the APK. That is also
# why GameActivity.getLibraries() returns {"cw_runtime"} and not SDL's default {"SDL2","main"}:
# both of those files are absent, and SDLActivity's loader would throw on the first one.
#
# The .java files are copied because SDL's Android support is half native and half Java, and
# the halves VERSION-CHECK EACH OTHER: SDLActivity.onCreate compares its own
# SDL_MAJOR/MINOR/MICRO constants against the library's nativeGetVersion() and refuses to
# start on a mismatch. That is the right behaviour, and it makes "built the .a from one tag and
# copied the .java from another" a boot failure that reads like a broken build. Copying both
# from one source tree is the only way to be sure, so this script does both.
#
# Usage:  tools/android/build_sdl2_android.sh [prefix] [java-dir]
#           prefix    default third_party/android/arm64-v8a/sdl2
#           java-dir  default third_party/android/sdl-java
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

VERSION=${CW_SDL2_VERSION:-2.32.10}
ABI=${CW_ABI:-arm64-v8a}
PREFIX=${1:-$ROOT/third_party/android/$ABI/sdl2}
JAVADIR=${2:-$ROOT/third_party/android/sdl-java}
WORK=${CW_SDL2_ANDROID_WORK:-/var/tmp/cw-sdl2-android}

# The SAME version the desktop builds against (tools/build_sdl2.sh), because a port that
# renders on Linux through one SDL and on Android through another has two windowing
# implementations to reason about, and every gotcha in docs/gotchas.md was written against one.
echo "==> SDL2 $VERSION for $ABI (static)"
cw_require_ndk "$ABI"

mkdir -p "$WORK"
cd "$WORK"

SRC=$WORK/SDL2-$VERSION
if [ ! -d "$SRC" ]; then
    TARBALL=SDL2-$VERSION.tar.gz
    if [ ! -f "$TARBALL" ]; then
        echo "==> fetching $TARBALL"
        curl -fL --retry 3 -o "$TARBALL.part" \
            "https://github.com/libsdl-org/SDL/releases/download/release-$VERSION/$TARBALL"
        mv "$TARBALL.part" "$TARBALL"
    fi
    echo "==> unpacking"
    tar xf "$TARBALL"
fi

BUILD=$WORK/build-$ABI
rm -rf "$BUILD" && mkdir -p "$BUILD"

echo "==> configuring"
# SDL_STATIC_PIC is the load-bearing flag. A static library that will be linked into a SHARED
# one has to be compiled -fPIC, and SDL's CMake only does that when asked: without it the link
# fails with a wall of "relocation R_AARCH64_ADR_PREL_PG_HI21 against external symbol" errors
# that name SDL's objects and say nothing about a missing flag.
#
# The *_SHARED options are SDL's "dlopen this backend at run time" switches. On Android there
# is nothing to dlopen — the video driver is the framework's surface, audio is OpenSL/AAudio,
# and the joystick path is the framework's input queue — so they are off and the backends are
# compiled in.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$CW_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$CW_API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DSDL_SHARED=OFF -DSDL_STATIC=ON -DSDL_STATIC_PIC=ON \
    -DSDL_TEST=OFF \
    -DSDL_HIDAPI=ON -DSDL_JOYSTICK=ON -DSDL_HAPTIC=ON -DSDL_SENSOR=OFF \
    -DSDL_AUDIO=ON -DSDL_VIDEO=ON \
    >"$BUILD/configure.log" 2>&1 || { tail -40 "$BUILD/configure.log"; exit 1; }

echo "==> building"
cmake --build "$BUILD" -j"$(nproc)" >"$BUILD/make.log" 2>&1 \
    || { tail -40 "$BUILD/make.log"; exit 1; }
rm -rf "$PREFIX"
# Guarded like the build above it, and for the same reason: this script runs under `set -euo
# pipefail`, so an unguarded failure here ends it without printing a word, and the only copy of
# what went wrong is in $BUILD/make.log — a file the CI step that called us never reads. The line
# above has just deleted $PREFIX, which makes the silent version of this failure worse than a
# failed build: the caller is left with no prefix at all and no reason.
cmake --install "$BUILD" >>"$BUILD/make.log" 2>&1 \
    || { echo "FAIL: cmake --install did not populate $PREFIX." >&2
         echo "      $PREFIX was deleted immediately before this, so nothing is left of the" >&2
         echo "      previous install either. Last 40 lines of $BUILD/make.log:" >&2
         tail -40 "$BUILD/make.log"; exit 1; }

echo "==> copying the Java half from the SAME tree"
rm -rf "$JAVADIR"
mkdir -p "$JAVADIR/org/libsdl/app"
cp "$SRC"/android-project/app/src/main/java/org/libsdl/app/*.java "$JAVADIR/org/libsdl/app/"
JAVA_COUNT=$(find "$JAVADIR" -name '*.java' | wc -l)

# --- the checks, all of them about the properties the runtime depends on -------------------
echo "==> verifying"

LIB=""
for d in lib lib64; do
    [ -f "$PREFIX/$d/libSDL2.a" ] && { LIB="$PREFIX/$d/libSDL2.a"; break; }
done
[ -n "$LIB" ] || { echo "FAIL: no libSDL2.a under $PREFIX/{lib,lib64}" >&2; exit 1; }

# The CMake package has to define SDL2::SDL2-static, because that is the target
# runtime/CMakeLists.txt links when it finds one. A prefix without it configures, finds
# SDL2, and then fails on "target SDL2::SDL2-static not found" — three steps away from the
# cause.
if [ ! -f "$PREFIX/lib/cmake/SDL2/sdl2-config.cmake" ] \
   && [ ! -f "$PREFIX/lib/cmake/SDL2/SDL2Config.cmake" ]; then
    echo "FAIL: $PREFIX has no SDL2 CMake package, so find_package(SDL2) will not resolve it." >&2
    exit 1
fi

# WHAT MUST BE IN libSDL2.a, AND WHAT MUST NOT — read off SDL 2.32.10's own source rather than off
# an assumption that its desktop layout carries over. It does not, in either direction:
#
#   * The desktop intuition is that SDL_main lives in SDL2main and the app links it. On Android the
#     only file SDLMAIN_SOURCES receives is src/main/android/SDL_android_main.c (CMakeLists.txt
#     line 1291), and in 2.32.10 that file is SEVEN LINES OF COMMENT: "As of SDL 2.0.6 this file is
#     no longer necessary." So libSDL2main.a is one empty object, and not linking it — which this
#     port deliberately does not — costs nothing.
#   * The JNI half lives in libSDL2.a itself, from src/core/android/SDL_android.c, which defines
#     Java_org_libsdl_app_SDLActivity_nativeRunMain. THAT is the symbol to require, and requiring
#     it is not pedantry: without it SDLActivity.nativeRunMain is an unsatisfied native method and
#     the app dies with UnsatisfiedLinkError before one line of ours runs.
#   * SDL_main is defined by SDL nowhere. nativeRunMain dlopens getMainSharedObject() and dlsyms
#     getMainFunction() out of the handle, so SDL_main is OURS — host/android_bridge.cpp defines it
#     extern "C". A libSDL2.a that DID define it would be a duplicate symbol at the final link, so
#     its absence is asserted rather than merely tolerated. This is the check that fired when it
#     was written the other way round, demanding a symbol SDL correctly does not provide.
#
# The dlopen-and-dlsym shape is load-bearing in three places that look unrelated, and all three are
# recorded here because this is the file a reader reaches for when the app dies before main:
# SDL_main must be exported with DEFAULT VISIBILITY in libcw_runtime.so (nothing in
# runtime/CMakeLists.txt passes -fvisibility=hidden, and that is a fact worth re-checking if this
# ever stops working); getLibraries() must name "cw_runtime" so that getMainSharedObject() derives
# libcw_runtime.so; and nativeRunMain puts "app_process" in argv[0] before the arguments it was
# given, so `--smoke` arrives as argv[1] — which is the slot main.cpp tests.
NMOUT=$("$CW_NM" --defined-only "$LIB" 2>/dev/null)
if ! printf '%s\n' "$NMOUT" | grep -q ' Java_org_libsdl_app_SDLActivity_nativeRunMain$'; then
    echo "FAIL: libSDL2.a does not define Java_org_libsdl_app_SDLActivity_nativeRunMain." >&2
    echo "      That symbol comes from src/core/android/SDL_android.c, so SDL's Android core" >&2
    echo "      did not make it into this build." >&2
    exit 1
fi
if printf '%s\n' "$NMOUT" | grep -q ' SDL_main$'; then
    echo "FAIL: libSDL2.a DEFINES SDL_main, and host/android_bridge.cpp defines it too — a" >&2
    echo "      duplicate symbol at the link of libcw_runtime.so. This SDL2 version put the" >&2
    echo "      Android main back into the library; either drop ours or stop linking this one." >&2
    exit 1
fi
echo "    nativeRunMain present; SDL_main correctly absent (ours to define and export)"

# Position independence, asserted rather than assumed: an -fPIC-less archive links fine into
# an executable and fails into a shared library, and this one goes into a shared library.
#
# Checked against SDL's own CMake cache, and NOT by grepping relocations out of the archive —
# which is what this used to do, and which was wrong twice over. `nm` prints symbols and never
# relocation types, so the pattern could not match anything and the check could not fail; and
# R_AARCH64_ADR_PREL_PG_HI21 is a PC-relative page relocation that legitimate PIC code also
# carries, so even with the right tool it was the wrong question. A check that cannot fail is
# worse than no check, because it is also a claim somebody will believe.
if [ -f "$BUILD/CMakeCache.txt" ]; then
    grep -q '^SDL_STATIC_PIC:BOOL=ON' "$BUILD/CMakeCache.txt" \
        || { echo "FAIL: SDL_STATIC_PIC is not ON in $BUILD/CMakeCache.txt, so libSDL2.a is" >&2
             echo "      non-PIC and linking it into libcw_runtime.so will fail on relocations" >&2
             echo "      that name SDL's objects. The flag is passed above; if the cache does" >&2
             echo "      not record it, this SDL2 version renamed the option." >&2
             exit 1; }
    echo "    SDL_STATIC_PIC=ON (from CMakeCache.txt)"
fi

# The Java version constants must match the library this script just built. This is the check
# that makes the "same tree" rule above enforceable rather than advisory.
JAVA_VERSION=$(sed -n 's/.*SDL_MINOR_VERSION = \([0-9]*\);.*/\1/p' \
    "$JAVADIR/org/libsdl/app/SDLActivity.java" | head -1)
LIB_VERSION=$(sed -n 's/#define SDL_MINOR_VERSION *\([0-9]*\).*/\1/p' \
    "$PREFIX/include/SDL2/SDL_version.h" | head -1)
if [ -n "$JAVA_VERSION" ] && [ -n "$LIB_VERSION" ] && [ "$JAVA_VERSION" != "$LIB_VERSION" ]; then
    echo "FAIL: SDLActivity.java is SDL 2.$JAVA_VERSION and the library is 2.$LIB_VERSION." >&2
    echo "      SDLActivity.onCreate refuses to start on a mismatch, so this would be a boot" >&2
    echo "      failure that reads like a broken APK. Both come from $SRC — re-run." >&2
    exit 1
fi

echo "==> result"
printf '    %-24s %8s KB\n' "libSDL2.a" "$(( $(stat -c%s "$LIB") / 1024 ))"
echo "    Java sources          $JAVA_COUNT files -> $JAVADIR"
echo "    SDL version           2.$LIB_VERSION (library and Java agree)"
echo "    symbols               SDL_main, SDLActivity JNI: present"
echo "    cmake package         $PREFIX/lib/cmake/SDL2"
echo "OK: $PREFIX"
echo
echo "Configure the runtime against it with:"
echo "    -DCW_SDL2_PREFIX=$PREFIX"
