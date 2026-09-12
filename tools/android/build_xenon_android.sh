#!/usr/bin/env bash
# Cross-build the three static libraries the runtime links out of the sibling XenonRecomp
# checkout: XenonUtils (the XEX loader), fmt and xxhash.
#
# WHY A SEPARATE BUILD TREE, and it is not tidiness. XenonRecomp's own `build/` holds the HOST
# XenonRecomp executable — the tool that turns config/CaseWest.toml and the game's XEX into the
# 228 generated translation units in ppc/. The runtime links three STATIC libraries out of the
# same project, and a static library is not portable between architectures: an x86-64
# libXenonUtils.a cannot go into an arm64 shared object. One directory cannot hold both, and
# cross-building into `build/` would replace the tool that produces the guest image with a
# binary the build machine cannot run — a self-inflicted wound that shows up the next time
# somebody regenerates ppc/ and finds the generator is for the wrong architecture.
#
# So: build-android-<abi>/, and app/build.gradle.kts passes it as -DXENON_BUILD (with the
# same default computed the same way, so the two cannot disagree about where the libraries are).
#
# WHY THE LOADER IS SHARED WITH THE DESKTOP BUILD AT ALL. This XEX is LZX-compressed under the
# all-zero DEVKIT key and tools/decrypt_xex.py cannot read it (gotchas 15/16), so the runtime
# uses XenonRecomp's loader — the same one tools/xex_image_dump uses. One loader in the project
# means one thing to keep patched, on every platform it runs on.
#
# Usage:  tools/android/build_xenon_android.sh [xenon-root] [build-dir]
#           defaults: $HOME/GithubRepo/XenonRecomp, <xenon-root>/build-android-<abi>
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

ABI=${CW_ABI:-arm64-v8a}
XENON=${1:-${CW_XENON_ROOT:-$HOME/GithubRepo/XenonRecomp}}
BUILD=${2:-${CW_XENON_BUILD:-$XENON/build-android-$ABI}}

echo "==> XenonRecomp static libraries for $ABI"
cw_require_ndk "$ABI"

if [ ! -f "$XENON/XenonUtils/image.cpp" ]; then
    cat >&2 <<EOF
FAIL: no XenonRecomp checkout at $XENON (expected XenonUtils/image.cpp).

  git clone https://github.com/hedge-dev/XenonRecomp $XENON

It is a public repository and a sibling checkout by convention — the same convention
runtime/CMakeLists.txt uses for XENON_ROOT on the desktop. Pass a path as the first argument
or set CW_XENON_ROOT to point somewhere else.
EOF
    exit 1
fi

# The HOST build is needed too, and this checks for it rather than assuming: without it there is
# no XenonRecomp executable to generate ppc/ from, and an Android build that silently used a
# stub image would produce an APK that boots and has no game in it. build_apk.sh decides which
# of the two a given run wants; this script only reports.
HOST_BUILD="$XENON/build"
if [ -x "$HOST_BUILD/XenonRecomp/XenonRecomp" ]; then
    echo "    host tool       present ($HOST_BUILD/XenonRecomp/XenonRecomp)"
else
    echo "    host tool       ABSENT — build it before generating a real ppc/ tree:"
    echo "                      cmake -S $XENON -B $HOST_BUILD && cmake --build $HOST_BUILD"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "==> configuring (cross, arm64)"
# The host tool is NOT built here on purpose. XenonRecomp's CMake builds its executable
# alongside the libraries, and asking an arm64 toolchain for a program the build machine then
# cannot run is wasted minutes at best; the target list below is what keeps it honest. If the
# project's CMake has no such target separation the build still succeeds, it just takes longer.
cmake -S "$XENON" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$CW_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$CW_API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    >"$BUILD/configure.log" 2>&1 || { tail -40 "$BUILD/configure.log"; exit 1; }

# PIC. These three archives are linked into a SHARED library, and a non-PIC object in one of them is
# a relocation error at that link — after all three have built, in a message that names XenonUtils
# and not a CMake variable. So the property is worth checking; the first version of the check just
# checked it in a way that could not be true.
#
# It grepped for `CMAKE_POSITION_INDEPENDENT_CODE:BOOL=ON`, and failed on a build that was correct.
# CMake records a -D variable that the PROJECT does not declare as a cache entry under the type
# UNINITIALIZED, not BOOL, so the line in the cache was
# `CMAKE_POSITION_INDEPENDENT_CODE:UNINITIALIZED=ON` and the grep for BOOL could never match.
# SDL_STATIC_PIC really is `:BOOL=` — SDL declares it with option() — which is why the equivalent
# check in build_sdl2_android.sh passes and this one did not. tools/android/build_dxc.sh greps
# ANDROID_STL with `:.*` for exactly this reason.
#
# So: the type is not assumed, and the property is ALSO asked of the compiler that will build these
# archives, because on Android it is the toolchain that decides position independence and not our
# flag. __PIC__ is defined exactly when the compiler generates PIC by default. A check that states
# which of the two it found is one that can be read; one that only asserts is one that has to be
# believed, and this one was believed three times before it was run.
PICLINE=$(grep -m1 '^CMAKE_POSITION_INDEPENDENT_CODE:' "$BUILD/CMakeCache.txt" || true)
MACROS=$("$CW_CLANG" -dM -E -x c /dev/null 2>/dev/null || true)
if grep -q ':.*=ON$' <<<"$PICLINE"; then
    echo "    PIC: $PICLINE (from CMakeCache.txt)"
elif grep -q '^#define __PIC__' <<<"$MACROS"; then
    echo "    PIC: the toolchain's own default — __PIC__ is defined by $CW_CLANG"
    echo "         (CMakeCache.txt says: ${PICLINE:-nothing; the variable is not in the cache})"
else
    echo "FAIL: these archives would not be position-independent, and they are linked into" >&2
    echo "      libcw_runtime.so, which is a shared library." >&2
    echo "      CMakeCache.txt says: ${PICLINE:-the variable is not in the cache at all}" >&2
    echo "      $CW_CLANG does not define __PIC__, so PIC is not the toolchain default either." >&2
    echo "      The flag is passed above; if the cache does not record it as ON, XenonRecomp's" >&2
    echo "      CMake overrode it, and the arm64 link will fail on relocations naming these" >&2
    echo "      archives rather than on anything that says PIC." >&2
    exit 1
fi

echo "==> building XenonUtils, fmt, xxhash"
# Named targets rather than the default all: `all` includes the recompiler executable and its
# test data, none of which an arm64 build can use. If a target name has moved upstream, the
# failure names the target instead of arriving as a missing .a three steps later.
TARGETS=(XenonUtils fmt xxhash)
for t in "${TARGETS[@]}"; do
    cmake --build "$BUILD" --target "$t" -j"$(nproc)" >>"$BUILD/make.log" 2>&1 \
        || { echo "FAIL: target $t did not build" >&2; tail -40 "$BUILD/make.log"; exit 1; }
done

echo "==> verifying the three libraries runtime/CMakeLists.txt expects"
# These paths are the contract, and they are runtime/CMakeLists.txt's rather than this script's:
# it composes them from CMAKE_STATIC_LIBRARY_{PREFIX,SUFFIX} under XENON_BUILD. A library that
# lands anywhere else is a configure-time FATAL_ERROR naming a path, so this fails here instead
# and says what to do about it.
P=lib
S=.a
MISSING=0
for lib in \
    "$BUILD/XenonUtils/${P}XenonUtils${S}" \
    "$BUILD/thirdparty/fmt/${P}fmt${S}" \
    "$BUILD/thirdparty/xxHash/cmake_unofficial/${P}xxhash${S}"
do
    if [ -f "$lib" ]; then
        printf '    %-52s %8s KB\n' "${lib#"$BUILD"/}" "$(( $(stat -c%s "$lib") / 1024 ))"
    else
        echo "    MISSING ${lib#"$BUILD"/}" >&2
        MISSING=1
    fi
done
if [ "$MISSING" -ne 0 ]; then
    cat >&2 <<EOF
FAIL: not all three static libraries are where runtime/CMakeLists.txt looks for them.

It composes those paths from XENON_BUILD, so either the upstream layout moved (find the .a
files under $BUILD and report where they are) or a target built somewhere unexpected. The
runtime refuses at configure time with the same paths named, so this check is here to fail
earlier and with a build log attached.
EOF
    exit 1
fi

# Architecture, asserted: an x86-64 archive in this tree would link-fail with "ignoring
# incompatible libXenonUtils.a", which names the file and not the reason.
ARCH=$("$CW_READELF" -h "$BUILD/XenonUtils/libXenonUtils.a" 2>/dev/null \
    | sed -n 's/.*Machine: *//p' | head -1 || true)
case "$ABI:$ARCH" in
    arm64-v8a:*AArch64*) : ;;
    x86_64:*X86-64*) : ;;
    *) echo "FAIL: libXenonUtils.a is ${ARCH:-unknown} but this build asked for $ABI." >&2; exit 1 ;;
esac

echo "==> result"
echo "    architecture    $ARCH"
echo "    build tree      $BUILD"
echo "OK"
echo
echo "Configure the runtime against it with:"
echo "    -DXENON_BUILD=$BUILD"
