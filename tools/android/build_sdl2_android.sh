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
cmake --install "$BUILD" >>"$BUILD/make.log" 2>&1

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

# SDL_main must be in there, and so must the JNI half that SDLActivity calls. Checking the
# symbols rather than trusting the flags: SDL's Android support is conditional on the video
# driver being built, and a build that quietly lost it produces a library that links and an app
# that dies in nativeSetupJNI.
for sym in SDL_main Java_org_libsdl_app_SDLActivity_nativeRunMain; do
    if ! "$CW_NM" --defined-only "$LIB" 2>/dev/null | grep -q " $sym\$"; then
        echo "FAIL: libSDL2.a does not define $sym." >&2
        echo "      The Android backend is not in this build." >&2
        exit 1
    fi
done

# Position independence, asserted rather than assumed: an -fPIC-less archive links fine into
# an executable and fails into a shared library, and this one goes into a shared library.
if "$CW_NM" "$LIB" 2>/dev/null | grep -q 'R_AARCH64_ADR_PREL_PG_HI21'; then
    echo "FAIL: libSDL2.a has non-PIC relocations — SDL_STATIC_PIC did not take." >&2
    exit 1
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
