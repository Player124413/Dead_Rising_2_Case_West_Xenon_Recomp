#!/usr/bin/env bash
# Shared NDK resolution for the tools/android/*.sh scripts. Source it, do not run it:
#
#     . "$(dirname "$0")/_ndk.sh"      # sets CW_NDK, CW_TOOLCHAIN_FILE, CW_TRIPLE, ...
#
# WHY ONE FILE. Every script here needs the same four answers — where the NDK is, which
# toolchain file to hand CMake, which clang to hand a configure script, and which `nm` can
# read an arm64 object — and four copies of that logic is four places for the answer to
# differ. The failure that produces is specific and confusing: an ffmpeg built against one
# NDK's clang and linked by another NDK's libc++ fails at link time with undefined symbols
# that name standard library functions, which reads like a broken ffmpeg.
#
# It fails loudly rather than guessing, because a wrong NDK is not a wrong version — it is a
# build that produces an APK whose libraries no device can load.

# Resolve the NDK from the variables the Android tooling actually uses, in the order the
# SDK's own documentation lists them. $ANDROID_NDK_HOME first because it is the only one that
# names an NDK rather than an SDK; then the side-by-side install under an SDK; then the
# deprecated $ANDROID_NDK_ROOT, last because it is the one most often left pointing at an NDK
# somebody uninstalled.
cw_find_ndk() {
    local candidates=()
    [ -n "${ANDROID_NDK_HOME:-}" ] && candidates+=("$ANDROID_NDK_HOME")
    [ -n "${CW_NDK:-}" ] && candidates+=("$CW_NDK")

    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ] && [ -d "$sdk/ndk" ]; then
        # The pinned version from gradle.properties if it is installed, else the newest.
        # Newest-first is a `sort -V` on the directory names, which for NDK naming
        # (27.2.12479018) is the same order as release order.
        local pinned
        pinned=$(sed -n 's/^cw\.ndkVersion=\(.*\)$/\1/p' \
            "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/android/gradle.properties" 2>/dev/null | head -1)
        if [ -n "$pinned" ] && [ -d "$sdk/ndk/$pinned" ]; then
            candidates+=("$sdk/ndk/$pinned")
        fi
        while IFS= read -r d; do candidates+=("$d"); done < <(
            find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -Vr)
    fi
    [ -n "${ANDROID_NDK_ROOT:-}" ] && candidates+=("$ANDROID_NDK_ROOT")

    local c
    for c in "${candidates[@]}"; do
        if [ -f "$c/build/cmake/android.toolchain.cmake" ]; then
            printf '%s' "$c"
            return 0
        fi
    done
    return 1
}

# Everything the scripts below need. Sets:
#   CW_NDK              the NDK root
#   CW_TOOLCHAIN_FILE   build/cmake/android.toolchain.cmake
#   CW_HOST_TAG         linux-x86_64 / darwin-x86_64
#   CW_CLANG, CW_CLANGXX, CW_AR, CW_NM, CW_STRIP   the llvm tools for arm64
#   CW_API              the platform level, matching android/app's minSdk
#   CW_TRIPLE           aarch64-linux-android<API>, ffmpeg's --target-os spelling
cw_require_ndk() {
    local abi="${1:-arm64-v8a}"
    CW_API="${CW_API:-26}"

    if [ -z "${CW_NDK:-}" ] || [ ! -f "${CW_NDK:-}/build/cmake/android.toolchain.cmake" ]; then
        CW_NDK=$(cw_find_ndk) || {
            cat >&2 <<'EOF'
FAIL: no Android NDK found. Looked at $ANDROID_NDK_HOME, $CW_NDK, $ANDROID_HOME/ndk/*,
      $ANDROID_SDK_ROOT/ndk/* and $ANDROID_NDK_ROOT.

  Android Studio:  SDK Manager -> SDK Tools -> NDK (Side by side)
  command line:    sdkmanager "ndk;27.2.12479018"
  then either      export ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018
  or               export ANDROID_HOME=/path/to/sdk

The version matters: the NDK decides which clang compiles the 228 generated translation
units of the recompiled guest image, and android/gradle.properties pins one.
EOF
            return 1
        }
    fi

    CW_TOOLCHAIN_FILE="$CW_NDK/build/cmake/android.toolchain.cmake"
    local uname_s
    uname_s=$(uname -s)
    case "$uname_s" in
        Linux)  CW_HOST_TAG=linux-x86_64 ;;
        Darwin) CW_HOST_TAG=darwin-x86_64 ;;
        *) echo "FAIL: unsupported build host $uname_s (the NDK ships Linux and macOS)" >&2; return 1 ;;
    esac

    local bin="$CW_NDK/toolchains/llvm/prebuilt/$CW_HOST_TAG/bin"
    [ -d "$bin" ] || { echo "FAIL: no $bin in $CW_NDK" >&2; return 1; }

    local target
    case "$abi" in
        arm64-v8a) target=aarch64 ;;
        x86_64)    target=x86_64 ;;
        *) echo "FAIL: this port builds arm64-v8a (and x86_64 for the emulator gate only)" >&2; return 1 ;;
    esac

    CW_BIN="$bin"
    CW_CLANG="$bin/${target}${CW_API}-clang"
    CW_CLANGXX="$bin/${target}${CW_API}-clang++"
    CW_AR="$bin/llvm-ar"
    CW_NM="$bin/llvm-nm"
    CW_STRIP="$bin/llvm-strip"
    CW_RANLIB="$bin/llvm-ranlib"
    CW_TRIPLE="${target}-linux-android${CW_API}"

    for t in "$CW_CLANG" "$CW_AR" "$CW_NM"; do
        [ -x "$t" ] || { echo "FAIL: $t is not executable — is the NDK complete?" >&2; return 1; }
    done

    echo "    NDK           $CW_NDK"
    echo "    abi / api     $abi / android-$CW_API"
    echo "    clang         $(basename "$CW_CLANG")"
}
