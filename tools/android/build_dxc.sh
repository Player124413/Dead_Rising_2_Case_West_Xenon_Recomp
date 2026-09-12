#!/usr/bin/env bash
# Cross-compile DXC's libdxcompiler.so for Android, into a prefix whose jniLibs/ Gradle packages.
#
# WHY THIS SCRIPT EXISTS, AND WHY IT IS AN LLVM BUILD
# ---------------------------------------------------
gpu/shader_translator.cpp dlopens `libdxcompiler.so` and calls `DxcCreateInstance` through it. It is
# the only compiler in the pipeline: the guest's shader microcode becomes HLSL through XenosRecomp's
# recompiler (compiled into the runtime) and HLSL becomes SPIR-V through DXC (loaded at run time).
# No DXC, no SPIR-V, no draws — the runtime refuses each translation with one log line and presents
# a black screen, which is the failure the first-run check exists to prevent.
#
# THERE IS NO PREBUILT ONE TO DOWNLOAD, and that is not a search that was given up on:
# microsoft/DirectXShaderCompiler publishes `linux_dxc_<date>.x86_64.tar.gz` in every release
# including v1.9.2607 (July 2026), and its arm64 artifacts are Windows `dxcompiler.dll`. The
# `renderbag/dxc-bin` mirror XenosRecomp vendors has the same shape — `lib/x64` and `bin/x64` for
# Linux. So the arm64 library this port needs is a build, not a download.
#
# WHY THE RUNTIME NEEDS IT ON THE PHONE EVEN WITH A COMPLETE CACHE
# ----------------------------------------------------------------
# The desktop release ships a cache and DXC both, and that is not belt-and-braces: D.1 established
# that the disc's `.vo` objects are TEMPLATES the title patches at bind time, so 0 of 104 runtime
# vertex shaders exist verbatim on disc. `vs_recipes.bin` recovers 102 of them as template+patch,
# and the remaining two are engine-synthesised with no template anywhere — they can only ever come
# from the translate-on-first-sight path (vk_renderer.cpp's `shaderjit`), which needs DXC on the
# device. And `vs_recipes.bin` is NOT in this repository: tools/vs_recipes.py generates it from a
# microcode dump of a machine that has run the game. So on a fresh phone every vertex shader is a
# first-sight translation, and without this library none of them can happen.
#
# WHAT IT COSTS. DXC is an LLVM/Clang fork. This builds the `dxcompiler` shared target only, with
# LLVM_TARGETS_TO_BUILD=None (the cache script's choice — DXC's SPIR-V backend is not an LLVM
# target), tests off, and Release. On a 4-core runner that is tens of minutes; .github/workflows
# caches the build directory so it happens once per DXC pin rather than once per commit.
#
# WHERE THE RESULT GOES. <prefix>/jniLibs/libdxcompiler.so. Gradle packages jniLibs into
# lib/<abi>/, which the framework extracts to nativeLibraryDir (extractNativeLibs is true — see
# app/build.gradle.kts's packaging block), and `HostPaths::ExeDir()` IS nativeLibraryDir on
# Android. `ExeDir()/libdxcompiler.so` is already one of shader_translator.cpp's candidates, so
# this needs no CW_DXC_LIB, no launcher row and no code change: the file being there is the whole
# integration.
#
# Usage:  tools/android/build_dxc.sh [prefix]
#           default third_party/android/<abi>/dxc
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

REPO=${CW_DXC_REPO:-https://github.com/microsoft/DirectXShaderCompiler}
# Pinned to a release tag rather than main. DXC's output is not byte-stable across versions, and
# while a cache lookup is keyed on the MICROCODE's hash rather than the compiler's version (so a
# v1.9-built cache and a v1.10-built device translation coexist fine), a moving pin would make
# "the same APK" a different artifact each week for no benefit.
REF=${CW_DXC_REF:-v1.9.2607}
ABI=${CW_ABI:-arm64-v8a}
PREFIX=${1:-$ROOT/third_party/android/$ABI/dxc}
WORK=${CW_DXC_WORK:-/var/tmp/cw-dxc}

# LLVM's link step is the memory-hungry one, not its compile step. Both are capped rather than
# left to -j$(nproc): a dxcompiler link that gets OOM-killed reports as a compiler crash with no
# mention of memory, on a build an hour deep.
JOBS=${CW_DXC_JOBS:-$(nproc)}
LINK_JOBS=${CW_DXC_LINK_JOBS:-2}

echo "==> DirectXShaderCompiler ($REF) for $ABI"
cw_require_ndk "$ABI"

mkdir -p "$WORK"
cd "$WORK"

SRC=$WORK/DirectXShaderCompiler
if [ ! -d "$SRC/.git" ]; then
    echo "==> cloning $REF (shallow — this is an LLVM fork and the history is most of it)"
    git clone --depth 1 --branch "$REF" "$REPO" "$SRC"
else
    echo "==> updating the existing clone"
    git -C "$SRC" fetch --depth 1 origin "refs/tags/$REF:refs/tags/$REF" >/dev/null 2>&1 || true
    git -C "$SRC" checkout "$REF" >/dev/null
fi
git -C "$SRC" log -1 --format='    commit %h  %ad  %s' --date=short

# Only the two submodules a SPIR-V-only build reads. googletest is for tests (off below) and
# DirectX-Headers is for the DXIL/Windows half, which does not exist on Android. SPIRV-Tools is
# pulled recursively because it has submodules of its own (its own SPIRV-Headers among them), and
# a half-populated SPIRV-Tools is a configure error naming a header rather than a submodule.
echo "==> submodules (SPIRV-Tools, SPIRV-Headers)"
git -C "$SRC" submodule update --init --recursive external/SPIRV-Tools external/SPIRV-Headers
for d in external/SPIRV-Tools external/SPIRV-Headers; do
    [ -n "$(ls -A "$SRC/$d" 2>/dev/null)" ] \
        || { echo "FAIL: $d is empty — git -C $SRC submodule update --init --recursive $d" >&2; exit 1; }
done

BUILD=$WORK/build-$ABI
rm -rf "$BUILD" && mkdir -p "$BUILD"

# cmake/caches/PredefinedParams.cmake is DXC's own set of required options, and it is passed with
# -C the way DXC's docs say to: it runs before the root CMakeLists, and the -D flags below override
# it (the cache script cannot override an explicit command-line parameter). What it gives us that
# matters here is LLVM_TARGETS_TO_BUILD=None, LLVM_ENABLE_EH/RTTI=ON and ENABLE_SPIRV_CODEGEN=ON.
EXTRA_TABLEGEN=()
if [ -n "${CW_DXC_HOST_TABLEGEN:-}" ]; then
    # An escape hatch for a machine that already has a matching host llvm-tblgen: LLVM's cross
    # build otherwise compiles its own into $BUILD/NATIVE, which is correct and costs minutes.
    EXTRA_TABLEGEN+=("-DLLVM_TABLEGEN_EXE=$CW_DXC_HOST_TABLEGEN")
fi

echo "==> configuring (tests off, lld, $JOBS compile / $LINK_JOBS link jobs)"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -C "$SRC/cmake/caches/PredefinedParams.cmake" \
    -DCMAKE_TOOLCHAIN_FILE="$CW_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$CW_API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DSPIRV_BUILD_TESTS=OFF \
    -DHLSL_INCLUDE_TESTS=OFF \
    -DLLVM_INCLUDE_TESTS=OFF \
    -DLLVM_INCLUDE_BENCHMARKS=OFF \
    -DCLANG_INCLUDE_TESTS=OFF \
    -DLLVM_PARALLEL_COMPILE_JOBS="$JOBS" \
    -DLLVM_PARALLEL_LINK_JOBS="$LINK_JOBS" \
    "${EXTRA_TABLEGEN[@]}" \
    >"$BUILD/configure.log" 2>&1 || { tail -60 "$BUILD/configure.log"; exit 1; }

# c++_shared and not c++_static: the runtime itself is built against c++_shared (AGP's default) and
# the APK ships one libc++_shared.so. A DXC statically linked against its own copy would carry a
# second C++ runtime into one process, which is the classic way to get two heaps and an abort in
# somebody else's destructor.
grep -q 'ANDROID_STL:.*c++_shared' "$BUILD/CMakeCache.txt" \
    || echo "    WARNING: CMakeCache does not record c++_shared — check the STL before shipping."

echo "==> building dxcompiler (this is the long step)"
cmake --build "$BUILD" --target dxcompiler -j"$JOBS" >"$BUILD/make.log" 2>&1 \
    || { tail -60 "$BUILD/make.log"; exit 1; }

# LLVM puts shared libraries in lib/ but the exact path has moved between DXC revisions, so this
# takes the first real file rather than a hardcoded one — and says which it found.
SO=$(find "$BUILD" -name 'libdxcompiler.so*' -type f | head -1)
[ -n "$SO" ] || { echo "FAIL: the build produced no libdxcompiler.so. See $BUILD/make.log" >&2; exit 1; }
echo "    built $SO"

rm -rf "$PREFIX"
mkdir -p "$PREFIX/jniLibs"
cp "$SO" "$PREFIX/jniLibs/libdxcompiler.so"

echo "==> verifying"
DXC=$PREFIX/jniLibs/libdxcompiler.so

# Architecture, from the ELF header rather than from which directory the build ran in: a configure
# that ignored the toolchain file produces host objects, and the failure would otherwise arrive on
# a device as a dlopen error naming a file that is plainly there.
MACHINE=$("$CW_NDK/toolchains/llvm/prebuilt/$CW_HOST_TAG/bin/llvm-readelf" -h "$DXC" 2>/dev/null \
    | sed -n 's/.*Machine: *//p' | head -1 || true)
if [ -n "$MACHINE" ]; then
    case "$ABI:$MACHINE" in
        arm64-v8a:*AArch64*) : ;;
        x86_64:*X86-64*) : ;;
        *) echo "FAIL: $DXC is $MACHINE but this build asked for $ABI." >&2; exit 1 ;;
    esac
fi

# The one symbol the runtime dlsyms. A libdxcompiler.so that loads but does not export it makes
# LoadDxcOnce skip the candidate and report "no dxcompiler library found" for a file that exists,
# which is a confusing place to end up after an hour of building.
if ! "$CW_NM" -D --defined-only "$DXC" 2>/dev/null | grep -q 'DxcCreateInstance'; then
    echo "FAIL: $DXC does not export DxcCreateInstance." >&2
    echo "      gpu/shader_translator.cpp dlsyms exactly that symbol; without it the library" >&2
    echo "      is skipped and every shader translation is refused." >&2
    exit 1
fi

echo "$REF" > "$PREFIX/version.txt"

echo "==> result"
printf '    %-26s %8s KB\n' "libdxcompiler.so" "$(( $(stat -c%s "$DXC") / 1024 ))"
echo "    architecture          ${MACHINE:-unverified} for $ABI"
echo "    exports               DxcCreateInstance"
echo "    STL                   c++_shared (one C++ runtime in the process, not two)"
echo "OK: $PREFIX"
echo
echo "Package it by pointing Gradle at the jniLibs directory (app/build.gradle.kts does this by"
echo "default for cw.dxcJniLibs). It lands in lib/$ABI/, is extracted to nativeLibraryDir at"
echo "install, and gpu/shader_translator.cpp finds it there as ExeDir()/libdxcompiler.so — no"
echo "CW_DXC_LIB and no launcher row. Verify on a device with:"
echo "    adb logcat -s CaseWest | grep shxlate"
echo "which prints the path it loaded, or the list of candidates it tried and failed."
