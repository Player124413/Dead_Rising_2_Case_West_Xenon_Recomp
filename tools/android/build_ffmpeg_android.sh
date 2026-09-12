#!/usr/bin/env bash
# Build the ONE ffmpeg this port uses, for Android, as static libraries.
#
# This is tools/build_ffmpeg_lgpl.sh with a cross toolchain, and the reasoning in that file's
# header applies unchanged: LGPL because this repo is PolyForm Noncommercial, and two
# libraries because the runtime calls fourteen ffmpeg functions and needs one decoder. What is
# different here is only the shape of the result — STATIC, because an APK that carried a
# libavcodec.so would be carrying a second copy of a library the runtime already links, and
# because Android's linker namespace makes an app-bundled .so a thing the app has to load by
# path rather than a thing the loader finds.
#
# The decoder list is the same (xma1 + xma2) and so is the reason for keeping xma1: finding 36
# established that the title submits XMA2, and a decoder that is absent fails as "unsupported
# codec" at the moment a player reaches whatever asset uses it. That failure arrives late and
# reads as a game bug.
#
# Usage:  tools/android/build_ffmpeg_android.sh [prefix]
#           default third_party/android/arm64-v8a/ffmpeg
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
# shellcheck source=_ndk.sh
. "$HERE/_ndk.sh"

VERSION=${CW_FFMPEG_VERSION:-8.1.2}
ABI=${CW_ABI:-arm64-v8a}
PREFIX=${1:-$ROOT/third_party/android/$ABI/ffmpeg}
WORK=${CW_FFMPEG_ANDROID_WORK:-/var/tmp/cw-ffmpeg-android}

echo "==> ffmpeg $VERSION for $ABI (static, LGPL, xma1+xma2 only)"
cw_require_ndk "$ABI"

mkdir -p "$WORK"
cd "$WORK"

SRC=$WORK/ffmpeg-$VERSION
if [ ! -d "$SRC" ]; then
    TARBALL=ffmpeg-$VERSION.tar.xz
    if [ ! -f "$TARBALL" ]; then
        echo "==> fetching $TARBALL"
        curl -fL --retry 3 -o "$TARBALL.part" "https://ffmpeg.org/releases/$TARBALL"
        mv "$TARBALL.part" "$TARBALL"
    fi
    echo "==> unpacking"
    tar xf "$TARBALL"
fi

BUILD=$WORK/build-$ABI
rm -rf "$BUILD" && mkdir -p "$BUILD"
cd "$BUILD"

echo "==> configuring"
# --disable-autodetect is the load-bearing flag here and on the desktop, and on Android it is
# load-bearing twice over: the build machine has a desktop ffmpeg, a desktop libva and a
# desktop OpenCL, and configure would happily use all three to produce a library that cannot be
# loaded on a phone. What comes out is checked below rather than trusted.
#
# --enable-pic IS LOAD-BEARING AND NOT THE DEFAULT. ffmpeg turns PIC on by itself only when it is
# building shared libraries (`enabled shared && enable pic` in its configure), and this build is
# --enable-static --disable-shared — so without the flag the objects come out non-PIC and the
# failure arrives at the FINAL LINK of libcw_runtime.so, twenty minutes of cross-compiling later,
# as a wall of relocation errors naming ffmpeg objects and saying nothing about a flag. The
# desktop script does not need it because it builds --enable-shared. Verified below against
# configure's own config.mak rather than against the flag we passed (gotcha 401: passing a flag
# and believing it is not the same as checking it took).
#
# The assembly flags are per-architecture, and getting them wrong is a configure failure that
# names a flag rather than a platform:
#
#   aarch64  NEON is baseline, ffmpeg's arm SIMD is written for the compiler's own assembler,
#            and --disable-x86asm disables a code path this target does not have. No nasm.
#   x86_64   ffmpeg's x86 SIMD is hand-written and normally needs nasm or yasm. --disable-x86asm
#            builds the same decoder from C instead, which is correct and slower — and the only
#            target this ABI has is the EMULATOR BOOT GATE, which runs `--smoke` and decodes no
#            audio at all. Installing nasm to optimise a decoder nobody calls in a build nobody
#            ships would be a build dependency bought for nothing.
case "$ABI" in
    arm64-v8a) ARCH=aarch64; ASMFLAGS="--enable-neon --enable-asm --disable-x86asm" ;;
    x86_64)    ARCH=x86_64;  ASMFLAGS="--enable-asm --disable-x86asm --disable-neon" ;;
    *) echo "FAIL: unsupported ABI $ABI" >&2; exit 1 ;;
esac
"$SRC/configure" \
    --prefix="$PREFIX" \
    --enable-static --disable-shared \
    --enable-pic \
    --enable-cross-compile \
    --target-os=android \
    --arch="$ARCH" \
    --cross-prefix="" \
    --cc="$CW_CLANG" \
    --cxx="$CW_CLANGXX" \
    --ar="$CW_AR" \
    --ranlib="$CW_RANLIB" \
    --strip="$CW_STRIP" \
    --sysroot="$CW_NDK/toolchains/llvm/prebuilt/$CW_HOST_TAG/sysroot" \
    $ASMFLAGS \
    --disable-everything \
    --disable-autodetect \
    --disable-programs --disable-doc \
    --disable-avdevice --disable-avformat --disable-avfilter \
    --disable-swscale --disable-swresample \
    --disable-network --disable-iconv \
    --enable-decoder=xma1,xma2 \
    --disable-gpl --disable-nonfree --disable-version3 \
    >"$BUILD/configure.log" 2>&1 || { tail -40 "$BUILD/configure.log"; exit 1; }

# PIC, against configure's OWN output, for the reason at the flag above.
if [ -f "$BUILD/ffbuild/config.mak" ]; then
    grep -q '^CONFIG_PIC=yes' "$BUILD/ffbuild/config.mak" \
        || { echo "FAIL: ffmpeg configure did not record CONFIG_PIC=yes. The static archives would" >&2
             echo "      be non-PIC and the final link of libcw_runtime.so would fail on" >&2
             echo "      relocations, twenty minutes from now, naming ffmpeg and not the flag." >&2
             exit 1; }
    echo "    CONFIG_PIC=yes (from ffbuild/config.mak)"
fi

# The licence check, against configure's OWN output rather than the flags we passed — passing
# --disable-gpl and believing it is the same mistake as believing an arm engaged because its
# variable appeared in the description (gotcha 401).
if grep -q '^#define CONFIG_GPL 1' config.h; then
    echo "FAIL: this build is GPL. It must not be shipped with a PolyForm repo." >&2
    exit 1
fi
if grep -q '^#define CONFIG_NONFREE 1' config.h; then
    echo "FAIL: this build is non-free and is not redistributable at all." >&2
    exit 1
fi

echo "==> building"
make -j"$(nproc)" >"$BUILD/make.log" 2>&1 || { tail -40 "$BUILD/make.log"; exit 1; }
rm -rf "$PREFIX"
# Guarded like the `make` above it: under `set -euo pipefail` an unguarded failure ends the script
# silently, and the only record of it is in $BUILD/make.log, which the calling CI step never reads.
# The line above has just deleted $PREFIX, so the silent version leaves no prefix and no reason.
make install >>"$BUILD/make.log" 2>&1 \
    || { echo "FAIL: make install did not populate $PREFIX." >&2
         echo "      $PREFIX was deleted immediately before this. Last 40 lines of" >&2
         echo "      $BUILD/make.log:" >&2
         tail -40 "$BUILD/make.log"; exit 1; }

echo "==> verifying"
AVC="$PREFIX/lib/libavcodec.a"
AVU="$PREFIX/lib/libavutil.a"
[ -f "$AVC" ] || { echo "FAIL: no $AVC" >&2; exit 1; }
[ -f "$AVU" ] || { echo "FAIL: no $AVU" >&2; exit 1; }

# The decoder has to be IN the archive. --enable-decoder=xma2 is a request, and a request that
# was not honoured produces a library that links and a game with no music — which is the exact
# shape of defect this project refuses at build time rather than discovers at play time.
# nm's output goes to a file and grep reads the file; it is NOT piped into grep -q. Under the
# `set -o pipefail` this script declares, a pipe into grep -q reports a symbol missing when it is
# present: grep -q exits the moment it matches, nm is still writing a 20 MB archive's symbol table
# into a 64 KiB pipe buffer, the write fails with EPIPE, and the pipeline's status becomes the
# producer's, which `if !` reads as "not found". It cost a CI run on exactly this check in
# build_sdl2_android.sh, where the full account is. Hoisting nm out of the loop also means running
# it once on libavcodec.a instead of three times.
NMTXT=$(mktemp)
trap 'rm -f "$NMTXT"' EXIT
"$CW_NM" --defined-only "$AVC" >"$NMTXT" 2>/dev/null \
    || { echo "FAIL: llvm-nm could not read $AVC, so the decoder check cannot run at all." >&2
         exit 1; }
for sym in xma2_decoder xma1_decoder avcodec_find_decoder; do
    if ! grep -q " $sym\$" "$NMTXT"; then
        echo "FAIL: libavcodec.a does not define $sym." >&2
        echo "      Check $BUILD/configure.log for what --disable-everything turned off." >&2
        exit 1
    fi
done

# ARM64 and nothing else. A configure that ignored --arch would happily produce x86-64 objects
# on this machine, and the failure would arrive at link time as "ignoring incompatible
# libavcodec.a" — a message that names the file and not the reason.
# ELF_MACHINE rather than reusing $ARCH: $ARCH is what configure was TOLD, this is what the
# archive says it IS, and the check below is only worth anything if the two are different
# variables. A configure that ignored --arch produces x86-64 objects on a build machine that has
# them, and the failure would otherwise arrive at link time as "ignoring incompatible
# libavcodec.a" — a message that names the file and not the reason.
ELF_MACHINE=$("$CW_READELF" -h "$AVC" 2>/dev/null | sed -n 's/.*Machine: *//p' | head -1 || true)
if [ -n "$ELF_MACHINE" ]; then
    case "$ABI:$ELF_MACHINE" in
        arm64-v8a:*AArch64*) : ;;
        x86_64:*X86-64*) : ;;
        *) echo "FAIL: $AVC is $ELF_MACHINE but this build asked for $ABI." >&2; exit 1 ;;
    esac
fi

echo "==> result"
printf '    %-22s %8s KB\n' "libavcodec.a" "$(( $(stat -c%s "$AVC") / 1024 ))"
printf '    %-22s %8s KB\n' "libavutil.a" "$(( $(stat -c%s "$AVU") / 1024 ))"
echo "    licence               CONFIG_GPL=0 CONFIG_NONFREE=0  (LGPL 2.1+)"
echo "    decoders              xma1, xma2 (both present in the archive)"
echo "    architecture          ${ELF_MACHINE:-unverified} for $ABI (configure was told $ARCH)"
echo "OK: $PREFIX"
echo
echo "Configure the runtime against it with:"
echo "    -DCW_FFMPEG_PREFIX=$PREFIX"
