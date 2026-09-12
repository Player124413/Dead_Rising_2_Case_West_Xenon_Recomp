#!/usr/bin/env bash
# Build libadrenotools and stage its hook libraries where the APK can carry them.
#
# WHAT IT BUYS, and why it is worth a build step. On Android there is no VK_DRIVER_FILES: the
# loader is /system/lib64/libvulkan.so and the driver it opens is
# /vendor/lib64/hw/vulkan.adreno.so, neither of which an app can influence. libadrenotools is
# the rootless answer — it loads the system loader into a private namespace and redirects the
# one dlopen that fetches the driver to a file in the app's own storage. That is what makes a
# Mesa Turnip build selectable per-app, which for THIS title is not a performance extra:
# gpu/vk_renderer.cpp requires Vulkan 1.3 and textureCompressionBC, and a phone whose platform
# loader predates 1.3 cannot get there from its own driver.
#
# OPTIONAL, and gpu/vk_shadow_android.cpp is written for its absence: without
# CW_HAVE_ADRENOTOOLS the runtime still builds, still runs, on the system driver, and says so
# in the boot log. This script failing is therefore not a blocker for an APK — it is a blocker
# for the launcher's driver row, and app/build.gradle.kts turns it into a BuildConfig field so
# the row says "unavailable in this build" instead of offering something that cannot work.
#
# --recursive IS REQUIRED. adrenotools' linkernsbypass is a git submodule and a SOURCE
# dependency: a shallow clone configures successfully and then fails to compile four files in,
# which is the worst shape of failure this script could have because it looks like a toolchain
# problem.
#
# Usage:  tools/android/build_adrenotools.sh [prefix]
#           default third_party/android/arm64-v8a/adrenotools
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

REPO=${CW_ADRENOTOOLS_REPO:-https://github.com/bylaws/libadrenotools}
REF=${CW_ADRENOTOOLS_REF:-main}
ABI=${CW_ABI:-arm64-v8a}
PREFIX=${1:-$ROOT/third_party/android/$ABI/adrenotools}
WORK=${CW_ADRENOTOOLS_WORK:-/var/tmp/cw-adrenotools}

# The four libraries adrenotools dlopens BY NAME out of the app's nativeLibraryDir. Not three,
# not "whichever the build produced": a missing one is not a build failure, it is a failure when
# the player picks a driver, reported from inside the driver loader. app/build.gradle.kts
# refuses to package an adrenotools prefix without all four, and this is the list it checks.
HOOKS=(libmain_hook.so libhook_impl.so libfile_redirect_hook.so libgsl_alloc_hook.so)

echo "==> libadrenotools ($REF) for $ABI"
cw_require_ndk "$ABI"

mkdir -p "$WORK"
cd "$WORK"

SRC=$WORK/libadrenotools
if [ ! -d "$SRC/.git" ]; then
    echo "==> cloning --recursive (linkernsbypass is a source dependency)"
    git clone --recursive "$REPO" "$SRC"
else
    echo "==> updating the existing clone"
    git -C "$SRC" fetch --recurse-submodules=yes origin "$REF" >/dev/null
    git -C "$SRC" checkout "$REF" >/dev/null
    git -C "$SRC" submodule update --init --recursive
fi
git -C "$SRC" log -1 --format='    commit %h  %ad  %s' --date=short

# A submodule that did not materialise is checked here rather than discovered as a compile
# error, because the error names a missing header and not a missing `--recursive`.
if [ ! -d "$SRC/linkernsbypass/src" ]; then
    echo "FAIL: libadrenotools/linkernsbypass is empty." >&2
    echo "      git -C $SRC submodule update --init --recursive" >&2
    exit 1
fi

BUILD=$WORK/build-$ABI
rm -rf "$BUILD" && mkdir -p "$BUILD"

echo "==> configuring"
# c++_shared and not c++_static, matching every other dependency here: this archive is linked into
# libcw_runtime.so, which AGP builds against c++_shared and ships with one libc++_shared.so. For a
# STATIC library the STL choice does not change which libc++ the objects end up using at final
# link, so this is not a rescue — it is one answer instead of two, in a build where a reader who
# sees c++_static in one script and c++_shared in four has to work out whether that is deliberate.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$CW_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$CW_API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DBUILD_SHARED_LIBS=OFF \
    >"$BUILD/configure.log" 2>&1 || { tail -40 "$BUILD/configure.log"; exit 1; }

echo "==> building"
cmake --build "$BUILD" -j"$(nproc)" >"$BUILD/make.log" 2>&1 \
    || { tail -40 "$BUILD/make.log"; exit 1; }

rm -rf "$PREFIX"
mkdir -p "$PREFIX/lib" "$PREFIX/jniLibs"

# The static library and the headers. adrenotools' own CMake has no install() rules in some
# revisions, so this copies what the build produced rather than running an install target that
# may not exist — and says which of the two it did.
if cmake --install "$BUILD" >>"$BUILD/make.log" 2>&1 && [ -d "$PREFIX/include/adrenotools" ]; then
    echo "    installed via cmake --install"
else
    echo "    no usable install target — copying the build outputs directly"
    cp -r "$SRC/include/adrenotools" "$PREFIX/include-tmp-adrenotools" 2>/dev/null || true
    mkdir -p "$PREFIX/include"
    cp -r "$SRC/include/adrenotools" "$PREFIX/include/"
    rm -rf "$PREFIX/include-tmp-adrenotools"
fi

# libadrenotools.a: the build names it either way depending on the revision's options.
LIB=""
for c in "$BUILD/libadrenotools.a" "$BUILD"/**/libadrenotools.a; do
    [ -f "$c" ] && { LIB="$c"; break; }
done
if [ -z "$LIB" ]; then
    LIB=$(find "$BUILD" -name 'libadrenotools.a' | head -1 || true)
fi
[ -n "$LIB" ] || { echo "FAIL: the build produced no libadrenotools.a under $BUILD" >&2; exit 1; }
cp "$LIB" "$PREFIX/lib/"

# The hook libraries. These are SHARED by nature — adrenotools dlopens them — so they are built
# as .so even in a static configuration, and they go into jniLibs/ where Gradle packages them
# into the APK's lib/<abi>/ directory, which is the app's nativeLibraryDir at run time.
echo "==> staging the hook libraries"
missing=0
for h in "${HOOKS[@]}"; do
    found=$(find "$BUILD" -name "$h" | head -1 || true)
    if [ -n "$found" ]; then
        cp "$found" "$PREFIX/jniLibs/$h"
        printf '    %-28s %8s KB\n' "$h" "$(( $(stat -c%s "$found") / 1024 ))"
    else
        echo "    MISSING $h" >&2
        missing=1
    fi
done
if [ "$missing" -ne 0 ]; then
    cat >&2 <<EOF
FAIL: adrenotools built without all four hook libraries.

They are dlopened BY NAME from the app's nativeLibraryDir at driver-load time, so an APK
missing one does not fail at build time or at startup — it fails when the player picks a
driver, in a message from inside the driver loader. Refusing here is what turns that into a
build failure. Look at $BUILD/make.log for the targets that did not build; the usual cause is
a revision whose CMake options renamed them.
EOF
    exit 1
fi

echo "==> verifying"
[ -f "$PREFIX/include/adrenotools/driver.h" ] \
    || { echo "FAIL: no $PREFIX/include/adrenotools/driver.h" >&2; exit 1; }
[ -f "$PREFIX/include/adrenotools/bcenabler.h" ] \
    || { echo "FAIL: no bcenabler.h — the BCn patch path will not compile" >&2; exit 1; }

# The two functions gpu/vk_shadow_android.cpp calls, checked as symbols in the archive: a
# header that declares them and a library that does not define them is a link error in a build
# 228 translation units deep, i.e. a long wait to be told something this check knows now.
# Captured, then matched with a here-string rather than piped into grep -q: under pipefail that pipe
# reports a symbol missing when it is present, because grep -q exits on match and leaves nm writing
# into a closed pipe. build_sdl2_android.sh has the full account; it cost a CI run there.
NMOUT=$("$CW_NM" --defined-only "$PREFIX/lib/libadrenotools.a" 2>/dev/null) \
    || { echo "FAIL: llvm-nm could not read $PREFIX/lib/libadrenotools.a." >&2; exit 1; }
for sym in adrenotools_open_libvulkan adrenotools_get_bcn_type adrenotools_patch_bcn; do
    if ! grep -q "$sym" <<<"$NMOUT"; then
        echo "FAIL: libadrenotools.a does not define $sym." >&2
        exit 1
    fi
done

echo "==> result"
printf '    %-28s %8s KB\n' "libadrenotools.a" \
    "$(( $(stat -c%s "$PREFIX/lib/libadrenotools.a") / 1024 ))"
echo "    headers                 driver.h, bcenabler.h"
echo "    hook libraries          ${#HOOKS[@]}/${#HOOKS[@]} -> $PREFIX/jniLibs"
echo "OK: $PREFIX"
echo
echo "Configure the runtime against it with:"
echo "    -DCW_ADRENOTOOLS_PREFIX=$PREFIX"
echo "and package the hooks with Gradle's jniLibs source set (app/build.gradle.kts does both)."
