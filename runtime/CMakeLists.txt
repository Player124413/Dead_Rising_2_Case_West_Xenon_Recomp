cmake_minimum_required(VERSION 3.20)

# CLANG IS REQUIRED, and this must be set before project() or CMake has already
# locked in the default compiler.
#
# Not a preference: XenonRecomp's ppc_context.h defines
#   #define PPC_FUNC_PROLOGUE() __builtin_assume(((size_t)base & 0x1F) == 0)
# and __builtin_assume is a Clang builtin. GCC has no spelling of it before the
# C++23 [[assume]] attribute, so every one of the 57,822 generated function bodies
# fails to compile under GCC — 16.1 emits "'__builtin_assume' was not declared in
# this scope" per function. Both earlier ports in this workspace build their
# runtimes with clang++ for the same reason.
#
# Override deliberately if you ever want to try otherwise:
#   cmake -DCMAKE_CXX_COMPILER=g++ ...
# ...AND NOT when a toolchain file was supplied. A toolchain file OWNS the compiler choice — that
# is the whole contract of the file — and this block runs BEFORE project(), which is where CMake
# reads it. So on Android the order is: this block, then project(), then the NDK's
# android.toolchain.cmake. With only `NOT DEFINED CMAKE_CXX_COMPILER` guarding it, an Android build
# on a machine that has a host clang++ (every CI runner does) sets CMAKE_CXX_COMPILER to
# /usr/bin/clang++ and hands that to the toolchain file to accept or override. Whether it overrides
# is a question about the NDK's internals that this build should not depend on, and getting it wrong
# is quiet in the worst way: the host compiler would be invoked with the NDK's --target and
# --sysroot flags, so it might even produce arm64 objects, and the first honest symptom would be a
# link failure about a standard library nobody chose. Checked again after project() below, because
# prevention here and detection there is how a wrong compiler gets named rather than inferred.
if(NOT DEFINED CMAKE_CXX_COMPILER AND NOT CMAKE_TOOLCHAIN_FILE)
    find_program(CW_CLANGXX clang++)
    if(NOT CW_CLANGXX)
        message(FATAL_ERROR
            "clang++ not found. The recompiled image needs Clang's __builtin_assume "
            "(see the comment above this message in runtime/CMakeLists.txt).")
    endif()
    set(CMAKE_CXX_COMPILER "${CW_CLANGXX}")
endif()

# C is enabled for exactly one file — thirdparty/o1heap/o1heap.c, the guest user
# heap. Without it CMake ignores the .c source entirely and the failure arrives at
# link time as three undefined o1heap* references, which reads like a missing
# library rather than a disabled language.
project(CaseWestRuntime C CXX)

# Two assertions about the compiler this build is actually going to use, made after project()
# because that is the first point at which CMake knows the answer. Both exist to replace a wall of
# errors with one sentence:
#
#   * NOT Clang: 57,822 generated function bodies each fail on __builtin_assume, one error per
#     function, in a build that takes minutes to reach its first failure. clang-cl on Windows
#     reports Clang and passes, as it must — build.yml uses exactly that.
#   * On ANDROID, a compiler that is not inside the NDK: the pre-project() block above no longer
#     overrides a toolchain file's choice, but this is the check that says so out loud if some
#     other path put a host compiler here. "prebuilt" is in every NDK's clang path
#     (<ndk>/toolchains/llvm/prebuilt/<host>/bin/clang++) on every host OS, and in no host
#     compiler's, so the test is a substring rather than a path comparison that would have to
#     know about separators.
if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(FATAL_ERROR
        "This runtime needs Clang (CMAKE_CXX_COMPILER_ID is \"${CMAKE_CXX_COMPILER_ID}\"). "
        "XenonRecomp's ppc_context.h uses __builtin_assume, which GCC has no spelling of before "
        "C++23's [[assume]]. Pass -DCMAKE_CXX_COMPILER=clang++ (or clang-cl on Windows).")
endif()
if(ANDROID)
    string(FIND "${CMAKE_CXX_COMPILER}" "prebuilt" CW_COMPILER_IN_NDK)
    if(CW_COMPILER_IN_NDK EQUAL -1)
        message(FATAL_ERROR
            "ANDROID build, but CMAKE_CXX_COMPILER is ${CMAKE_CXX_COMPILER}, which is not inside "
            "the NDK (no \"prebuilt\" in the path). Something chose a HOST compiler for a "
            "cross-build. AGP passes -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake; "
            "if you are configuring by hand, pass it too.")
    endif()
    message(STATUS "compiler: ${CMAKE_CXX_COMPILER} (${CMAKE_CXX_COMPILER_ID})")
endif()

# Windows: the CRT deprecates getenv, strerror, fopen and friends in favour of its own
# _s variants. This runtime uses the standard ones throughout and deliberately keeps
# doing so — they are what the POSIX build uses and a divergence here would be a
# portability difference invented for a warning. Silencing it is not ignoring a defect;
# it is declining an invitation to use a Microsoft-only API.
if(WIN32)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS _CRT_NONSTDC_NO_WARNINGS)

    # NOMINMAX is not a style preference, it is a correctness requirement. windows.h
    # defines `min` and `max` as function-like MACROS, so every `std::max(a, b)` in
    # this runtime expands to `std::((a)>(b)?(a):(b))` and the compiler reports
    # `expected unqualified-id` — pointing at our line, naming nothing, and saying
    # nothing about macros. Three of the nine broken files were only this.
    #
    # WIN32_LEAN_AND_MEAN drops the RPC, OLE, and socket headers nothing here uses,
    # which is both faster and a smaller surface for exactly this kind of collision.
    add_compile_definitions(NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

# host/win_compat.h is FORCE-INCLUDED into every runtime source on Windows rather than
# added to nine files by hand. Two reasons, and the second is the real one:
#
#   * it has to come before anything that pulls windows.h in transitively, and
#     "transitively" is not a property any single #include line can guarantee;
#   * a header that must be included first is a rule someone eventually forgets, and
#     the failure is a compile error in a file nobody touched.
#
# It compiles to nothing on every other platform, so the flag is Windows-only and the
# POSIX build is bit-identical with or without the file existing.
# Phase 1 + the GPU + phase 3's window/present/input seam. There is still no
# RENDERER — that is phase 5, and a blank window is this phase's expected result.
# See docs/runtime-plan.md.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Sibling XenonRecomp checkout: supplies simde (which ppc_context.h includes) and,
# from phase 1, the XEX loader in XenonUtils.
set(XENON_ROOT "$ENV{HOME}/GithubRepo/XenonRecomp" CACHE PATH "XenonRecomp source root")
# Sibling XenonLive checkout: libxlive, which owns the account, the durable
# achievement record and the server sync. Same convention as XENON_ROOT — a
# checkout beside this one, statically linked, no shared runtime library.
set(XLIVE_ROOT "$ENV{HOME}/GithubRepo/XenonLive" CACHE PATH "XenonLive source root")

# CW_XLIVE — link libxlive, or build the OFF surface from kernel/xlive_stub.cpp.
#
# ON is the developer's machine: the account, the achievements and the session layer
# are real, and CW_NO_XLIVE=1 at RUNTIME is what turns them off. That runtime switch
# is why OFF is safe to offer at all — xlive_glue.h documents every function here as
# optional and says that with it off they behave "as if no account exists, which is
# byte-for-byte what this runtime did before". OFF reaches that same state at LINK
# time instead, and it is not the same measurement: a CW_XLIVE=OFF build never had
# the implementation, so it cannot reproduce anything measured against the runtime
# one. The stub says so out loud in the boot log (see tools/gen_xlive_stub.py).
#
# Two builds need it, and both are builds where the checkout cannot exist:
#   * CI. XenonLive is not public, so no runner can clone it, and without this option
#     configure stops at XLIVE_ROOT before compiling anything — a red workflow that
#     proves nothing about the port. .github/workflows/build.yml passes -DCW_XLIVE=OFF.
#   * Android. docs/android-port-plan.md milestone A0 ships without it: achievements
#     and co-op sessions are not what makes the title playable on a phone, and
#     libxlive's own dependency closure is one more thing to cross-compile before a
#     first frame is drawn. .github/workflows/android.yml passes it too.
option(CW_XLIVE "Link libxlive (XenonLive) for the account/achievement/session layer" ON)
if(CW_XLIVE)
    set(CW_XLIVE_SOURCES
        kernel/xlive_glue.cpp        # XenonLive: the account and achievements
        kernel/xlive_session.cpp     # the XGI session surface; off unless CW_XLIVE_COOP=1
        kernel/xlive_social.cpp      # the XLiveBase surface: friends, invites, mutes
        kernel/xlive_stats.cpp       # the leaderboard read path
        kernel/xlive_net.cpp)        # the socket family over the punched path, and QoS
else()
    set(CW_XLIVE_SOURCES
        kernel/xlive_stub.cpp)       # generated by tools/gen_xlive_stub.py — see CW_XLIVE above
    message(STATUS "CW_XLIVE=OFF: no libxlive, kernel/xlive_stub.cpp answers every XLive call")
endif()
# CW_PPC_DIR overrides where the generated code comes from — CI points it at a
# STUB tree from tools/gen_stub_ppc.py, because no runner may hold the game
# (release-plan E.1). Unset, the sibling ppc/ is used as always.
set(CW_PPC_DIR "" CACHE PATH "Generated ppc tree (default: sibling ppc/)")
if(CW_PPC_DIR)
    set(PPC_DIR "${CW_PPC_DIR}")
else()
    set(PPC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ppc")
endif()

if(NOT EXISTS "${PPC_DIR}/ppc_func_mapping.cpp")
    message(FATAL_ERROR
        "No generated code in ${PPC_DIR}. It is gitignored (156 MB) — regenerate it:\n"
        "  mkdir -p ppc && cd config && \\\n"
        "    ${XENON_ROOT}/build/XenonRecomp/XenonRecomp CaseWest.toml \\\n"
        "    ${XENON_ROOT}/XenonUtils/ppc_context.h")
endif()

# RelWithDebInfo by default. Fable 2 measured a plain Debug build running recompiled
# VMX decode at roughly one movie frame per minute, and the addr2line workflow both
# earlier ports rely on needs the symbols.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

# THE RELEASE BUILD TYPE (Case Zero release-plan A.3, imported).
#
# `Release` is redefined here, deliberately and loudly, because CMake's default for
# it would be a PERFORMANCE CHANGE dressed as a packaging decision:
#
#   CMake's Release        -O3 -DNDEBUG
#   ours                   -O2 -g -DNDEBUG, then the debug info split off and the
#                          shipped binary stripped
#
# -O2, NOT -O3: every performance number both ports have recorded was measured at
# -O2; shipping -O3 would invalidate the corpus for a saving nobody has measured.
# -g KEPT then SPLIT: the shipped file is stripped, the .debug sits beside it, and
# crash_report's host pc still addr2lines. And it makes the gate free and decisive:
# -g does not affect code generation and --strip-debug does not touch .text, so the
# release .text is BYTE-IDENTICAL to the dev build's —
# tools/release_text_identity.sh is that check.
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -g -DNDEBUG"
    CACHE STRING "Flags used by the CXX compiler during RELEASE builds." FORCE)
set(CMAKE_C_FLAGS_RELEASE   "-O2 -g -DNDEBUG"
    CACHE STRING "Flags used by the C compiler during RELEASE builds." FORCE)

# Split the debug info off the shipped executable. Defaults ON for Release and OFF
# everywhere else, so a developer never has to think about it and a release never
# has to remember it.
#
# Android is OFF even for Release. The same job is done there by a different tool at a
# different time: Gradle strips the native libraries as it packages the APK, using the
# NDK's own llvm-strip, and it keeps an unstripped copy under the module's
# intermediates/merged_native_libs — which is where an addr2line run against a crash from
# a phone has to look anyway, since that is the exact binary that was in the APK. Doing it
# twice would put a .debug file next to a library inside an APK, where nothing can read it
# and it costs download size.
if(CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT ANDROID)
    set(CW_SPLIT_DEBUG_DEFAULT ON)
else()
    set(CW_SPLIT_DEBUG_DEFAULT OFF)
endif()
option(CW_SPLIT_DEBUG
       "Emit cw_runtime.debug and strip the executable (release packaging)"
       ${CW_SPLIT_DEBUG_DEFAULT})

file(GLOB PPC_SOURCES CONFIGURE_DEPENDS "${PPC_DIR}/*.cpp")
list(LENGTH PPC_SOURCES PPC_TU_COUNT)
message(STATUS "Recompiled image: ${PPC_TU_COUNT} translation units")

# The image as a static library. Kept separate from the executable so that a
# runtime-only edit does not risk a rebuild of 227 TUs, and so the link step is
# visibly a *link* of the image rather than a compile of it.
add_library(ppc_image STATIC ${PPC_SOURCES})

target_include_directories(ppc_image PUBLIC
    ${PPC_DIR}                       # ppc_recomp_shared.h, ppc_context.h, ppc_config.h
    ${XENON_ROOT}/thirdparty/simde   # ppc_context.h includes <x86/avx.h> etc.
)

# SSE4.1 is not optional ON x86-64: ppc_context.h maps the guest's VMX unit onto simde,
# and several lowerings (the packs, min/max_epu32 used by the saturating vector
# arithmetic) are SSE4.1 intrinsics. Without it simde silently falls back to scalar
# emulation, which is correct but very slow.
#
# On AArch64 those flags are not merely unnecessary, they are an ERROR: clang rejects
# `-msse4.1` for an arm64 target outright, so an ungated list stops the Android build at
# the first of 228 generated TUs with a message about a flag and nothing about a platform.
# The guest's VMX unit still gets vectorised — XenonRecomp's ppc_context.h routes simde
# through NEON on arm64, which is the same mapping by a different spelling, and the reason
# this runtime can be recompiled for a phone at all. There is no arm64 equivalent of
# -msse4.1 to add: NEON is baseline, not an opt-in.
#
# -Wno-unused-* silences the recompiler's own temporaries (`ea`, unused `temp`), not
# our code. They are emitted per-function by design and there are tens of thousands.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    target_compile_options(ppc_image PRIVATE
        -Wno-unused-variable -Wno-unused-but-set-variable)
else()
    target_compile_options(ppc_image PRIVATE
        -msse4.1 -mavx
        -Wno-unused-variable -Wno-unused-but-set-variable)
endif()

# cpu/timebase.h is force-included over the RECOMPILED sources ONLY. `mftb` is
# lowered to __rdtsc(), which on this host runs ~70x faster than the console's
# 49.875 MHz timebase; the header shadows it with a scaled counter. The runtime's
# own sources must keep the real __rdtsc, which is why this is a source property on
# ${PPC_SOURCES} and not a target-wide option. Gotcha #1.
# The forced include, spelled for the driver in use. `-include <path>` is the GNU
# driver's flag; clang-cl parses the path as a second INPUT FILE and fails with
# "cannot specify '/Fo...' when compiling multiple source files" — an error that names
# the output rather than the flag, and which stopped all 228 recompiled TUs on the
# first Windows build (gotcha 494). /FI is the clang-cl spelling. MSVC is true for
# clang-cl in CMake, which is what we want: it selects on the DRIVER, not the vendor.
if(MSVC)
    set_source_files_properties(${PPC_SOURCES} PROPERTIES COMPILE_OPTIONS
        "/FI${CMAKE_CURRENT_SOURCE_DIR}/cpu/timebase.h")
else()
    set_source_files_properties(${PPC_SOURCES} PROPERTIES COMPILE_OPTIONS
        "-include;${CMAKE_CURRENT_SOURCE_DIR}/cpu/timebase.h")
endif()

# CW_PPC_O3 — build the RECOMPILED IMAGE at -O3; the runtime's own sources keep the
# build type's -O2. Exists because the 2026-08-28 uninstrumented perf profile showed the
# guest render thread SATURATED while the pump idled 23% at the HUD scene — i.e. on this
# title the recompiled code, not the renderer, can be the frame's pacing thread, which is
# the opposite of Case Zero's regime (their phase5-notes §6dm). GCC's last -O wins, so
# this appends cleanly after the build type's -O2; CW_DEBUG_TUS still wins over both
# because source-file properties are appended after target options.
option(CW_PPC_O3 "Build the recompiled guest image at -O3" OFF)
if(CW_PPC_O3)
    target_compile_options(ppc_image PRIVATE -O3)
    message(STATUS "CW_PPC_O3: recompiled image at -O3")
endif()

# CW_DEBUG_TUS — build named recompiled TUs at -O0 -fno-inline so a fault inside one
# resolves to an exact line, and every line in those files carries its original PPC
# mnemonic as a comment. Asura's Wrath established that host backtraces through
# recompiled frames at -O2 are not merely imprecise but actively misleading (its
# addr2line output named a function that was never called). Semicolon-separated
# basenames:  cmake .. -DCW_DEBUG_TUS="ppc_recomp.148.cpp"
set(CW_DEBUG_TUS "" CACHE STRING "Recompiled TU basenames to build at -O0")
foreach(tu IN LISTS CW_DEBUG_TUS)
    if(EXISTS "${PPC_DIR}/${tu}")
        message(STATUS "CW_DEBUG_TUS: ${tu} at -O0 -fno-inline")
        set_source_files_properties("${PPC_DIR}/${tu}" PROPERTIES COMPILE_OPTIONS
            "-include;${CMAKE_CURRENT_SOURCE_DIR}/cpu/timebase.h;-O0;-fno-inline")
    else()
        message(WARNING "CW_DEBUG_TUS: no such TU ${PPC_DIR}/${tu}")
    endif()
endforeach()

# SDL2 — the window, the present and the pad (phase 3). REQUIRED by default rather
# than optional-with-a-fallback: the runtime can run headless (CW_NO_WINDOW=1, and
# every gate in this project is a log diff that needs no pixels), but a build that
# silently loses its window would look exactly like a run whose input stopped
# working, and that is a session wasted. Configure with -DCW_WINDOW=OFF to say
# "headless on purpose" out loud.
option(CW_WINDOW "Build the SDL window / present / input seam (phase 3)" ON)

# CW_SDL2_PREFIX — build against the SDL2 from tools/build_sdl2.sh (release builds).
# Exists because Fedora's libSDL2-2.0.so.0 is `sdl2-compat`, a shim that dlopen()s
# libSDL3 at run time — invisible to ldd, and Case Zero's first bundle died on it in
# a clean container. Developing against the system copy stays the default.
set(CW_SDL2_PREFIX "" CACHE PATH
    "Prefix of an SDL2 built by tools/build_sdl2.sh (release builds)")
if(CW_SDL2_PREFIX)
    list(PREPEND CMAKE_PREFIX_PATH "${CW_SDL2_PREFIX}")
    message(STATUS "SDL2: ${CW_SDL2_PREFIX} (built from source, not sdl2-compat)")
endif()

# On Android CW_SDL2_PREFIX is not a release nicety, it is the ONLY way: there is no SDL2
# in the NDK, and an unprefixed find_package would search the host's own paths and could
# well find the x86-64 desktop copy that built this toolchain's SDL2. Configuring against
# that is not a warning, it is a link failure naming half of SDL's symbols, after the whole
# 228-TU image has already been compiled for arm64. tools/android/build_sdl2_android.sh
# writes the prefix this expects, static, ABI-matched, with the Android JNI half in it.
if(ANDROID AND CW_WINDOW AND NOT CW_SDL2_PREFIX)
    message(FATAL_ERROR
        "Android needs an SDL2 built for the SAME ABI and toolchain — the host's copy "
        "cannot be linked into an arm64 library.\n"
        "  tools/android/build_sdl2_android.sh <prefix>   (static, arm64-v8a)\n"
        "then configure with:\n"
        "  -DCW_SDL2_PREFIX=<prefix>\n"
        "See docs/android-port-plan.md §3 for what the prefix must contain.")
endif()

if(CW_WINDOW)
    if(ANDROID)
        # The NDK's build/cmake/android.toolchain.cmake sets, at its line 102,
        #
        #     set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
        #
        # and ONLY re-roots every path find_package searches under CMAKE_FIND_ROOT_PATH, which the
        # same file points at the NDK sysroot. The CW_SDL2_PREFIX prepended to CMAKE_PREFIX_PATH
        # above is an absolute path inside this checkout, so it is searched as <sysroot>/<prefix>,
        # which does not exist, and the result is a failure indistinguishable from "SDL2 was never
        # built" — which is what it reads as from the message alone, and is what it read as in the
        # run that first hit this, on a prefix that had just been built and cached. BOTH searches
        # the re-rooted path and the real one; PATHS names the prefix outright and NO_DEFAULT_PATH
        # keeps the host's own SDL2 out of the answer, which is what the comment above this block
        # has always wanted and what an unprefixed find_package cannot promise.
        set(_cw_sdl2_find_mode "${CMAKE_FIND_ROOT_PATH_MODE_PACKAGE}")
        set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
        find_package(SDL2 QUIET CONFIG PATHS "${CW_SDL2_PREFIX}" NO_DEFAULT_PATH)
        set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE "${_cw_sdl2_find_mode}")
    else()
        find_package(SDL2 QUIET)
    endif()
    if(NOT SDL2_FOUND)
        # Say what was looked at. "Not found" is three different situations — no prefix given, a
        # prefix that does not exist, and a prefix that exists but was built without the CMake
        # package or without the static library — and they have different fixes, which the person
        # reading a CI annotation cannot otherwise tell apart.
        set(_cw_sdl2_evidence "  CW_SDL2_PREFIX   ${CW_SDL2_PREFIX}")
        if(NOT CW_SDL2_PREFIX)
            string(APPEND _cw_sdl2_evidence "   (EMPTY)")
        elseif(NOT IS_DIRECTORY "${CW_SDL2_PREFIX}")
            string(APPEND _cw_sdl2_evidence
                "\n  that directory DOES NOT EXIST, so nothing was ever installed into it")
        else()
            string(APPEND _cw_sdl2_evidence "\n  that directory exists; what is in it:")
            foreach(_cw_sdl2_f
                    lib/libSDL2.a
                    lib64/libSDL2.a
                    lib/cmake/SDL2/sdl2-config.cmake
                    lib/cmake/SDL2/SDL2Config.cmake
                    include/SDL2/SDL.h)
                if(EXISTS "${CW_SDL2_PREFIX}/${_cw_sdl2_f}")
                    string(APPEND _cw_sdl2_evidence "\n    present   ${_cw_sdl2_f}")
                else()
                    string(APPEND _cw_sdl2_evidence "\n    MISSING   ${_cw_sdl2_f}")
                endif()
            endforeach()
        endif()
        if(ANDROID)
            message(FATAL_ERROR
                "SDL2 for ${ANDROID_ABI} was not found, and it is what phase 3's window, present "
                "seam and pad are built on.\n${_cw_sdl2_evidence}\n"
                "The host's SDL2 cannot be linked into an ${ANDROID_ABI} library; build one for "
                "this ABI and toolchain:\n"
                "  tools/android/build_sdl2_android.sh <prefix>\n"
                "android/app/build.gradle.kts passes -DCW_SDL2_PREFIX for it.")
        else()
            message(FATAL_ERROR
                "SDL2 not found, and it is what phase 3's window, present seam and pad "
                "are built on.\n${_cw_sdl2_evidence}\n"
                "  Fedora:  sudo dnf install SDL2-devel   (sdl2-compat-devel also works)\n"
                "  Debian:  sudo apt install libsdl2-dev\n"
                "Or build without it deliberately:  cmake -S runtime -B runtime/build "
                "-DCW_WINDOW=OFF")
        endif()
    endif()
endif()

# ---------------------------------------------------------------------------
# Release D.2 — in-process shader translation (gpu/shader_translator.cpp).
#
# XenosRecomp's translator (MIT, the sibling checkout this project already patches) is
# compiled INTO the runtime — source, not a library, because upstream builds it as an
# executable and the 2,573-line recompiler TU is the whole dependency. Its build
# normally supplies a precompiled pch; gpu/xenos_pch.h is that pch minus smol-v, zstd,
# xxhash and dxcapi (see its header comment), force-included the same way.
set(XENOS_ROOT "$ENV{HOME}/GithubRepo/XenosRecomp" CACHE PATH "XenosRecomp source root")
if(NOT EXISTS "${XENOS_ROOT}/XenosRecomp/shader_recompiler.cpp")
    message(FATAL_ERROR
        "No XenosRecomp checkout at ${XENOS_ROOT} (need XenosRecomp/shader_recompiler.cpp "
        "for in-process shader translation, release D.2).\n"
        "  git clone https://github.com/hedge-dev/XenosRecomp ${XENOS_ROOT}\n"
        "or point at it:  cmake -DXENOS_ROOT=/path/to/XenosRecomp ...")
endif()

# shader_common.h is EMBEDDED, not read at run time. The HLSL the translator emits
# begins with this file's bytes, and the runtime's cache key contract is byte identity
# with the caches tools/build_shader_spv.sh built from the very same file — an
# external copy that drifted or went missing would silently produce different SPIR-V.
# Regenerated at configure time; copy_if_different so an unchanged file does not
# recompile the translator on every configure.
file(READ "${XENOS_ROOT}/XenosRecomp/shader_common.h" CW_SHADER_COMMON_HEX HEX)
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," CW_SHADER_COMMON_BYTES "${CW_SHADER_COMMON_HEX}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/generated/cw_shader_common_embedded.h.in"
    "// Generated at configure time from ${XENOS_ROOT}/XenosRecomp/shader_common.h.\n"
    "// DO NOT EDIT — edit the source file and re-run cmake.\n"
    "#pragma once\n"
    "inline constexpr unsigned char g_czShaderCommonH[] = {${CW_SHADER_COMMON_BYTES}};\n"
    "inline constexpr unsigned long g_czShaderCommonHSize = sizeof(g_czShaderCommonH);\n")
execute_process(COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${CMAKE_CURRENT_BINARY_DIR}/generated/cw_shader_common_embedded.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/generated/cw_shader_common_embedded.h")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${XENOS_ROOT}/XenosRecomp/shader_common.h")

# The source list is a VARIABLE and the target type is decided afterwards, because
# Android builds this same list as a SHARED LIBRARY and every other platform builds it
# as an executable. Two add_*() calls sharing one list, rather than two lists that have
# to be kept equal by somebody who remembers: the runtime is one program whose entry
# point differs (see the comment at CwRuntimeMain in main.cpp), and a source list that
# forks is how a port ends up building a slightly different game per platform.
#
# The four Android files below are in the list for EVERY platform, not only Android.
# Each one wraps its whole body in `#if defined(__ANDROID__)` and compiles to nothing
# elsewhere, which buys a real thing: desktop CI compile-checks them. A signature that
# drifts between cpu/touch_input.h and window.cpp's call site, or between the shadow
# table in gpu/vk_shadow_android.h and the 95 entry points it declares, is a red build
# on a runner that exists instead of a red build on a phone.
set(CW_RUNTIME_SOURCES
    main.cpp
    cpu/timebase.cpp
    cpu/guest_thread.cpp
    cpu/crash_report.cpp
    cpu/thread_budget.cpp            # one thread budget for the whole runtime (imported from Case Zero part 55)
    cpu/pc_options_cw.cpp            # the Visuals panel's guest-side half (Case Zero part 60, default path only)
    cpu/fe_probe.cpp                 # frontend cFEMeter widget probe (findings 35-39)
    cpu/gap_probe.cpp                # counts calls into the 7 functions with untranslated instructions
    # cpu/guest_probe.cpp and cpu/debug_tunables.cpp are Case Zero's per-title reverse
    # engineering (89 hardcoded guest addresses between them and the D3D pair below).
    # They live in runtime/port-pending/ until Case West's own addresses are derived —
    # read that directory's README, especially the part about sub_82475718, which exists
    # in BOTH images and would have linked silently to an unrelated function.
    cpu/debug_tunables_cw.cpp
    cpu/native_kbm.cpp               # part 92 (Case Zero): native KB/M via the title's own input layer
    cpu/camera_fov.cpp               # Case Zero parts 62/108 (imported part 11): the game-side fov substitution — the ultrawide/16:10 CULLING fix; addresses re-derived here
    cpu/fence_wait.cpp               # Case Zero part 107 (imported part 11): the Draw Thread fence wait parked on a futex — addresses re-derived here
    cpu/rumble_guest.cpp             # Case Zero part 108 (imported part 11): the rumble tick at 30 Hz (CW_RUMBLE_TICK_HZ) + the probe (CW_RUMBLE_TRACE) — addresses re-derived here
    kernel/klog.cpp
    kernel/memory.cpp
    kernel/heap.cpp
    kernel/kobject.cpp
    kernel/imports.cpp
    kernel/file_imports.cpp
    kernel/vfs.cpp
    kernel/audio.cpp
    audio/xma_decoder.cpp            # phase A/V; ffmpeg's XMA2, one per XMA context
    audio/audio_out.cpp              # phase A/V; stubs itself out without CW_HAVE_SDL
    kernel/content.cpp
    kernel/xex_imports.cpp
    ${CW_XLIVE_SOURCES}              # libxlive's five sources, or the generated OFF stub — see CW_XLIVE
    kernel/guest_log.cpp             # the title's own log, printed with CW_GUEST_LOG=1
    kernel/seat_trace.cpp            # the co-op seating trace, CW_SEAT_LOG=1 (temporary)
    gpu/vd.cpp
    gpu/pm4.cpp
    # gpu/parallel_record.cpp — part 7's per-range secondaries + deferred replay, PARKED
    # (part 8): superseded by Case Zero's part-89 recorder ("resolve serial, record
    # parallel"), which subsumes the deferred-replay win and adds worker recording that
    # our own 2c flip proved null under the range partition. The file stays as the
    # campaign's record; CW_VK_NO_PAR_RECORD=1 is the new serial control arm.
    gpu/vk_renderer.cpp              # phase 5; inert unless CW_VKDRAW=1
    gpu/shader_translator.cpp        # release D.2; --translate-shaders and D.4's seam
    gpu/shader_prebuild.cpp          # release D.3; the first-run disc shader pass
    "${XENOS_ROOT}/XenosRecomp/shader_recompiler.cpp"   # XenosRecomp's own translator
    host/host_paths.cpp              # release A.1: every path anchored to the exe
    host/first_run.cpp               # release A.2: what is missing, where it goes
    host/stfs_extract.cpp            # release E (§2.3 step 2): the in-process unpack
    host/overlay_gen.cpp             # release §0: first-run KB/M overlay generation
    gpu/d3d_draw_stub.cpp
    host/settings.cpp                # the Visuals panel's persistent store (imported from Case Zero part 60)
    host/window.cpp                  # phase 3; compiles to stubs without CW_HAVE_SDL
    host/png_icon.cpp                # the window icon from the player's own X_IMAGEID_GAME.PNG
    host/log_file.cpp                # part 105: stderr tee'd to cw_runtime.log beside the root
    kernel/import_stubs.cpp          # generated by tools/gen_import_stubs.py
    thirdparty/o1heap/o1heap.c

    # The Android four. Compiled on EVERY platform, inert on every platform that is not
    # Android — see the comment above this list for why that is worth the extra objects.
    cpu/touch_input.cpp              # the fourth input source: decoded touch -> XInput snapshot
    host/android_bridge.cpp          # the JNI seam (rumble, first-run progress) + stderr -> logcat
    host/android_perf.cpp            # the governor: internal-resolution ladder + thermal ceiling
    gpu/vk_shadow_android.cpp        # the loader table, so the renderer needs no linked libvulkan
)

# The one place the two platforms' builds differ structurally. Android has no process to
# start: SDLActivity's nativeRunMain dlopens the library that getLibraries() names last and
# calls SDL_main in it, so the runtime is a SHARED LIBRARY whose SDL_main lives in
# host/android_bridge.cpp. Everywhere else the OS execs `main` in main.cpp. The Kotlin side
# names the same file ("cw_runtime"), and window.cpp's CW_ANDROID_SELF_LIB says it a third
# time with the platform's lib prefix attached — three places that must agree, which is why
# the name is the target's own and not a fourth literal.
if(ANDROID)
    add_library(cw_runtime SHARED ${CW_RUNTIME_SOURCES})
    set_target_properties(cw_runtime PROPERTIES OUTPUT_NAME "cw_runtime")
else()
    add_executable(cw_runtime ${CW_RUNTIME_SOURCES})
endif()

# host/win_compat.h, force-included into the RUNTIME's C++ sources and nothing else.
#
# Scoped to this target rather than the directory, for two reasons. The 228 generated
# TUs have no windows.h collisions and no need for it, and pulling windows.h into every
# one of them is a large build-time tax for nothing. And o1heap.c is C: a bare
# add_compile_options reaches it too, and a C++ header force-fed to a C compiler
# produces two thousand errors inside the header itself.
#
# The generator expression keeps it off C even within this target, so the one .c file
# here stays untouched.
if(WIN32)
    if(MSVC)
        target_compile_options(cw_runtime PRIVATE
            "$<$<COMPILE_LANGUAGE:CXX>:/FI${CMAKE_CURRENT_SOURCE_DIR}/host/win_compat.h>")
    else()
        target_compile_options(cw_runtime PRIVATE
            "$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/host/win_compat.h>")
    endif()
endif()

# overlay_gen.cpp uses a TU-internal exception for its gate ladder (caught at its
# two public entry points). clang-cl and MSVC default exceptions OFF; the flag is
# scoped to the one file that needs it.
if(MSVC)
    set_source_files_properties(host/overlay_gen.cpp PROPERTIES COMPILE_OPTIONS "/EHsc")
endif()

# D.2's two translation units get their include paths as SOURCE properties, not
# target-wide: the XenosRecomp directory carries generically-named headers (shader.h,
# constant_table.h) that nothing else in this runtime should ever resolve by accident.
# INCLUDE_DIRECTORIES on a source file APPENDS to the target's.
set(CW_XLATE_INCLUDES
    "${XENOS_ROOT}/XenosRecomp"
    "${XENOS_ROOT}/thirdparty/dxc-bin/inc"
    "${XENON_ROOT}/thirdparty/fmt/include"   # the fmt whose static lib is linked below
    "${CMAKE_CURRENT_BINARY_DIR}/generated")
set_source_files_properties("${XENOS_ROOT}/XenosRecomp/shader_recompiler.cpp"
    PROPERTIES INCLUDE_DIRECTORIES "${CW_XLATE_INCLUDES}")
set_source_files_properties(gpu/shader_translator.cpp
    PROPERTIES INCLUDE_DIRECTORIES "${CW_XLATE_INCLUDES}")
# Upstream's TU compiles against its pch and with its warning set; the force-include
# is the pch substitution (same /FI vs -include split as cpu/timebase.h above).
# shader_translator.cpp needs -fms-extensions on the GNU driver for dxcapi's
# WinAdapter (__declspec(uuid)); clang-cl has MS extensions on by default.
if(MSVC)
    set_source_files_properties("${XENOS_ROOT}/XenosRecomp/shader_recompiler.cpp"
        PROPERTIES COMPILE_OPTIONS
        "/FI${CMAKE_CURRENT_SOURCE_DIR}/gpu/xenos_pch.h;-Wno-switch;-Wno-unused-variable;-Wno-null-arithmetic")
else()
    set_source_files_properties("${XENOS_ROOT}/XenosRecomp/shader_recompiler.cpp"
        PROPERTIES COMPILE_OPTIONS
        "-include;${CMAKE_CURRENT_SOURCE_DIR}/gpu/xenos_pch.h;-Wno-switch;-Wno-unused-variable;-Wno-null-arithmetic")
    set_source_files_properties(gpu/shader_translator.cpp
        PROPERTIES COMPILE_OPTIONS "-fms-extensions")
endif()
# dlopen for the DXC library (a no-op string on Windows, where LoadLibrary is used).
target_link_libraries(cw_runtime PRIVATE ${CMAKE_DL_LIBS})

# libxlive, when CW_XLIVE is on. REQUIRED at configure time for the same reason SDL2,
# Vulkan and ffmpeg are: kernel/xlive_*.cpp is in the build, so a missing checkout
# should be a build message naming what to clone, not a wall of undefined symbols. It
# is inert at RUNTIME without an account, and CW_NO_XLIVE=1 turns it off entirely.
# With CW_XLIVE=OFF the five sources are not in the build at all and neither is this
# requirement — that is the whole point of the option (see it, up by XLIVE_ROOT).
if(CW_XLIVE)
    if(NOT EXISTS "${XLIVE_ROOT}/client/CMakeLists.txt")
        message(FATAL_ERROR
            "No XenonLive checkout at ${XLIVE_ROOT}.\n"
            "  git clone <XenonLive> ${XLIVE_ROOT}\n"
            "or point XLIVE_ROOT somewhere else.\n"
            "Or build without libxlive on purpose — CI and the Android port both do:\n"
            "  cmake ... -DCW_XLIVE=OFF   (answers every XLive call as if signed out)")
    endif()
    add_subdirectory("${XLIVE_ROOT}/client" "${CMAKE_CURRENT_BINARY_DIR}/xlive")
    target_link_libraries(cw_runtime PRIVATE xlive::client)
endif()

if(CW_WINDOW)
    target_compile_definitions(cw_runtime PRIVATE CW_HAVE_SDL=1)
    # Which SDL2 target, and the reason there is a choice: SDL's own CMake package names
    # the static build SDL2::SDL2-static and the shared one SDL2::SDL2, and only one of the
    # two exists in any given prefix. Android is always the static one — an APK cannot carry
    # a libSDL2.so the framework did not put there without also shipping it, and shipping
    # two copies of SDL in one APK means two ideas about which surface exists. Everywhere
    # else the developer's system copy is shared and stays shared.
    #
    # SDL2main is deliberately NOT linked, on ANY platform. On Android the reason is that there is
    # nothing in it: the only file SDL's CMake puts in SDLMAIN_SOURCES for Android is
    # src/main/android/SDL_android_main.c, and in 2.32.10 that file is seven lines of comment —
    # "As of SDL 2.0.6 this file is no longer necessary." The JNI half is in libSDL2.a itself, from
    # src/core/android/SDL_android.c, which is why tools/android/build_sdl2_android.sh requires
    # Java_org_libsdl_app_SDLActivity_nativeRunMain THERE and forbids SDL_main there.
    #
    # And the mechanism is dlopen-plus-dlsym, not a call: nativeRunMain dlopens whatever
    # getMainSharedObject() names and dlsyms whatever getMainFunction() returns. That is what makes
    # SDL_main's VISIBILITY load-bearing — it has to be an exported dynamic symbol of
    # libcw_runtime.so, so no -fvisibility=hidden may reach this target, and a symbol that is
    # present but hidden produces "Couldn't find function SDL_main in library libcw_runtime.so" in
    # logcat and no other symptom at all. On the desktops the reason not to link SDL2main is
    # simpler: it exists to provide a `main` that forwards to SDL_main, and main.cpp already IS that
    # `main`, so linking it would put two definitions of `main` on one link line.
    if(TARGET SDL2::SDL2-static)
        target_link_libraries(cw_runtime PRIVATE SDL2::SDL2-static)
        message(STATUS "SDL2 target: SDL2::SDL2-static")
    elseif(TARGET SDL2::SDL2)
        target_link_libraries(cw_runtime PRIVATE SDL2::SDL2)
        message(STATUS "SDL2 target: SDL2::SDL2")
    else()
        message(FATAL_ERROR
            "find_package(SDL2) succeeded but defined neither SDL2::SDL2 nor "
            "SDL2::SDL2-static. On Android that means CW_SDL2_PREFIX points at a prefix "
            "built without SDL2_STATIC=ON; tools/android/build_sdl2_android.sh sets it.")
    endif()
endif()

# Vulkan — phase 5's renderer. REQUIRED for the same reason SDL2 is: the renderer is
# off at runtime unless CW_VKDRAW=1, so a machine that cannot render still builds and
# runs every gate this project owns, but a build that silently lost the renderer would
# be indistinguishable from a run where the renderer declined to start.
#
# ANDROID IS THE EXCEPTION, and it is an exception about the LIBRARY, not the API: the
# renderer needs vulkan.h and it does NOT need -lvulkan. Linking the system loader there
# would put a second, hookless copy of Vulkan in the process beside the one
# gpu/vk_shadow_android.cpp loads — which is the exact failure libadrenotools exists to
# avoid, since a custom Turnip driver only reaches a loader that went looking for it. The
# shadow table resolves every entry point at run time from whichever loader was chosen,
# so the headers come from the NDK's own sysroot (it ships vulkan/vulkan_core.h) and no
# find_package runs. The cost is that nothing checks at LINK time that a symbol exists,
# which is why the table is generated from the header by tools/gen_vk_shadow.py rather
# than written out: a name the loader does not export shows up as a null pointer the
# table reports at init, in the boot log, instead of as a crash three frames in.
if(ANDROID)
    message(STATUS "Vulkan: headers from the NDK sysroot, NO linked loader "
                   "(gpu/vk_shadow_android.cpp resolves the table at run time)")
else()
    find_package(Vulkan QUIET)
    if(NOT Vulkan_FOUND)
        message(FATAL_ERROR
            "Vulkan not found, and phase 5's renderer is built on it.\n"
            "  Fedora:  sudo dnf install vulkan-loader-devel vulkan-headers\n"
            "  Debian:  sudo apt install libvulkan-dev")
    endif()
    target_link_libraries(cw_runtime PRIVATE Vulkan::Vulkan)
endif()

# ffmpeg (libavcodec) — the XMA2 decoder for guest audio, phase A/V. REQUIRED, for
# the third time and the same reason as SDL2 and Vulkan: the game's own mixer reads
# PCM that only a decoder can write, so a build that quietly lost the codec produces
# a silent game that is indistinguishable from the defect this replaced. Refusing at
# CONFIGURE time is how "no sound" stays a build message instead of a mystery.
# Headers live under /usr/include/ffmpeg on Fedora; pkg-config supplies the paths.
# CW_FFMPEG_PREFIX — build against the LGPL ffmpeg from tools/build_ffmpeg_lgpl.sh
# rather than the system one (docs/release-plan.md §4, A.4).
#
# This is a RELEASE requirement, not a preference. Fedora's ffmpeg is configured
# --enable-gpl, which conflicts with this repo's PolyForm Noncommercial licence the
# moment the two are shipped together; and its dependency closure is 120 shared
# objects (x264, x265, librsvg, cairo, pango, OpenCL, VA-API…) against the FOURTEEN
# ffmpeg functions this runtime calls. The purpose-built one is libavcodec + libavutil,
# 1.4 MB, linking libm and libc and nothing else.
#
# Developing against the system copy stays the default: it is the same 8.1.2, so the
# headers are identical and there is no second variable.
set(CW_FFMPEG_PREFIX "" CACHE PATH
    "Prefix of an ffmpeg built by tools/build_ffmpeg_lgpl.sh (release builds)")
if(CW_FFMPEG_PREFIX)
    # The prefix has to describe a real ffmpeg, and "real" is checked by the two things
    # the direct lookup below actually opens. The .pc file is the cheap first answer and a
    # desktop prefix always has one; a CROSS-COMPILED prefix is allowed to be missing it,
    # because a static ffmpeg built for arm64-android has the headers and the archives and
    # no reason to describe itself to a pkg-config that will never be asked. Falling
    # through to the header/archive check is what keeps that from being an error that names
    # a file the build does not need.
    if(NOT EXISTS "${CW_FFMPEG_PREFIX}/lib/pkgconfig/libavcodec.pc"
       AND NOT EXISTS "${CW_FFMPEG_PREFIX}/include/libavcodec/avcodec.h")
        message(FATAL_ERROR
            "CW_FFMPEG_PREFIX=${CW_FFMPEG_PREFIX} has neither lib/pkgconfig/libavcodec.pc "
            "nor include/libavcodec/avcodec.h.\n"
            "  Desktop: tools/build_ffmpeg_lgpl.sh ${CW_FFMPEG_PREFIX}\n"
            "  Android: tools/android/build_ffmpeg_android.sh ${CW_FFMPEG_PREFIX}")
    endif()
    message(STATUS "ffmpeg: ${CW_FFMPEG_PREFIX} (LGPL, xma1+xma2 only)")
endif()

# Android has no system ffmpeg and no pkg-config to ask about one, so the prefix is not a
# release nicety here either — it is the only source of libavcodec. Same shape of failure
# as SDL2 above, and the same reason to stop at configure rather than at link: the XMA
# decoder is fourteen functions, and a build missing them is a game with no sound.
if(ANDROID AND NOT CW_FFMPEG_PREFIX)
    message(FATAL_ERROR
        "Android needs an ffmpeg cross-compiled for the same ABI — there is no system "
        "libavcodec on a phone and no pkg-config to find one.\n"
        "  tools/android/build_ffmpeg_android.sh <prefix>   (static, LGPL, xma1+xma2 only)\n"
        "then configure with:\n"
        "  -DCW_FFMPEG_PREFIX=<prefix>")
endif()

# Two ways to find ffmpeg, and which one runs is decided by whether we were TOLD
# where it is.
#
#   CW_FFMPEG_PREFIX set  -> look under it directly. No pkg-config.
#   not set               -> pkg-config, the system copy, the developer default.
#
# The direct path exists because Windows has no pkg-config, and adding one as a build
# dependency to satisfy a lookup we can do ourselves would be paying for a tool to
# tell us something we already passed in on the command line. It also removes the
# .pc-file path-translation problem entirely: an MSYS2-built .pc names
# `/c/cz/...`, which a Windows CMake cannot open.
if(CW_FFMPEG_PREFIX)
    find_path(CW_AVCODEC_INC libavcodec/avcodec.h
              PATHS "${CW_FFMPEG_PREFIX}/include" NO_DEFAULT_PATH REQUIRED)
    # Both spellings, because the two toolchains name the import library
    # differently and neither is wrong: libavcodec.so on Linux, avcodec.lib from
    # ffmpeg's MSVC toolchain.
    find_library(CW_AVCODEC_LIB NAMES avcodec libavcodec
                 PATHS "${CW_FFMPEG_PREFIX}/lib" "${CW_FFMPEG_PREFIX}/bin"
                 NO_DEFAULT_PATH REQUIRED)
    find_library(CW_AVUTIL_LIB NAMES avutil libavutil
                 PATHS "${CW_FFMPEG_PREFIX}/lib" "${CW_FFMPEG_PREFIX}/bin"
                 NO_DEFAULT_PATH REQUIRED)
    add_library(cw_ffmpeg INTERFACE)
    target_include_directories(cw_ffmpeg INTERFACE "${CW_AVCODEC_INC}")
    target_link_libraries(cw_ffmpeg INTERFACE "${CW_AVCODEC_LIB}" "${CW_AVUTIL_LIB}")
    add_library(CW::ffmpeg ALIAS cw_ffmpeg)
    message(STATUS "ffmpeg libs: ${CW_AVCODEC_LIB}  ${CW_AVUTIL_LIB}")
else()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavcodec libavutil)
    add_library(cw_ffmpeg INTERFACE)
    target_link_libraries(cw_ffmpeg INTERFACE PkgConfig::FFMPEG)
    add_library(CW::ffmpeg ALIAS cw_ffmpeg)
endif()
target_link_libraries(cw_runtime PRIVATE CW::ffmpeg)

target_include_directories(cw_runtime PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/o1heap
    ${XENON_ROOT}/XenonUtils         # image.h (loader), xbox.h (guest structs), xex.h
)

target_link_libraries(cw_runtime PRIVATE ppc_image)

# Windows: VirtualAlloc2 / MapViewOfFile3 / UnmapViewOfFileEx are the placeholder
# mapping API kernel/memory.cpp uses for the guest's three aliased physical views.
# They live in kernelbase.dll and are NOT exported by kernel32.lib — the import
# library is onecore.lib. Verified by compiling and RUNNING a probe on the build
# laptop before any of this was written, because "the API exists" and "the API links"
# are different claims and only the second one builds.
if(WIN32)
    # onecore  — VirtualAlloc2 / MapViewOfFile3 / UnmapViewOfFileEx (kernel/memory.cpp).
    #            In kernelbase.dll and NOT exported by kernel32.lib.
    # dbghelp  — SymInitialize / SymFromAddr, dladdr's replacement in the crash reporter.
    # synchronization — WaitOnAddress / WakeByAddressSingle (cpu/fence_wait.cpp, the
    #            Draw Thread's parked fence wait). Also reachable through onecore, but
    #            named here so a future link-line edit cannot drop it silently.
    target_link_libraries(cw_runtime PRIVATE onecore dbghelp synchronization)

    # The compiler-rt builtins. The recompiled image does 128-bit arithmetic (the guest's
    # multiply-high and divide lower to __int128), and clang-cl does NOT link its own
    # builtins library by default on Windows the way clang does on Linux — so __udivti3
    # comes out undefined at link time with nothing to say where it came from.
    #
    # Located from the compiler in use rather than hardcoded, because the path carries
    # the LLVM major version and would rot on the next upgrade.
    get_filename_component(CW_LLVM_BIN "${CMAKE_CXX_COMPILER}" DIRECTORY)
    file(GLOB CW_CLANG_RT "${CW_LLVM_BIN}/../lib/clang/*/lib/windows/clang_rt.builtins-x86_64.lib")
    if(CW_CLANG_RT)
        list(GET CW_CLANG_RT 0 CW_CLANG_RT_LIB)
        message(STATUS "compiler-rt builtins: ${CW_CLANG_RT_LIB}")
        target_link_libraries(cw_runtime PRIVATE "${CW_CLANG_RT_LIB}")
    else()
        message(WARNING
            "clang_rt.builtins-x86_64.lib not found under ${CW_LLVM_BIN}/../lib/clang/. "
            "Expect an undefined __udivti3 at link time: the recompiled image needs "
            "128-bit division and clang-cl does not link its builtins by default.")
    endif()
endif()

# Android: the two libraries this runtime's own code calls into, and the third it
# deliberately does not link.
#
#   log      — __android_log_print, eight call sites, all of them the diagnostic surface:
#              host/android_bridge.cpp's stderr mirror (the reason ANY of this runtime's
#              fprintf output is readable on a phone) and gpu/vk_shadow_android.cpp's
#              loader log. Without it the link fails on a symbol whose name does not
#              mention logging.
#   android  — NOT linked. Nothing in this runtime calls a libandroid function: no
#              ANativeWindow, no AAssetManager, no AConfiguration. The window comes from
#              SDL, which links `android` through its own imported target, and a second
#              copy of that dependency declared here would be a claim about code that does
#              not exist. If a future file needs one, it is added with the file.
#   vulkan   — NOT linked, and see the Vulkan block above: linking it would put a second,
#              hookless loader in the process beside the one the shadow table chose.
if(ANDROID)
    # The Android system libraries SDL2 needs, listed explicitly rather than left for the imported
    # SDL2::SDL2-static target to carry. SDL's own CMake declares them PRIVATE on that target
    # (target_link_libraries(SDL2-static PRIVATE ${EXTRA_LIBS} ...)), and CMake does record a static
    # library's PRIVATE link dependencies for its consumers — but whether the INSTALLED package
    # re-exports them is a property of SDL's packaging that this build neither controls nor can
    # check from here. What a missing one produces is a wall of undefined symbols at the final link
    # (ANativeWindow_*, AAssetManager_*, SL_*, gl*), naming SDL's internals and no variable of ours;
    # and linking a system library that is also linked transitively is not an error, so being
    # explicit costs nothing. The set is 2.32.10's own EXTRA_LIBS for Android.
    target_link_libraries(cw_runtime PRIVATE log android dl OpenSLES GLESv1_CM GLESv2)

    # libadrenotools (bylaws, BSD-2-Clause) — the custom GPU driver loader, i.e. the thing
    # that makes "Turnip" a row in the launcher instead of a rumour. OPTIONAL at build time
    # and gpu/vk_shadow_android.cpp is written for that: with CW_HAVE_ADRENOTOOLS undefined
    # it still builds, still runs, on the system driver, and says so in the boot log. That
    # is the honest failure. The dishonest one is a link error nobody can diagnose on a
    # phone, which is what an unconditional dependency would produce.
    #
    # The prefix has to be built for the same ABI by tools/android/build_adrenotools.sh,
    # which clones it --recursive: adrenotools' own linkernsbypass submodule is a source
    # dependency, and a shallow clone configures and then fails to compile four files in.
    set(CW_ADRENOTOOLS_PREFIX "" CACHE PATH
        "Prefix of a libadrenotools built by tools/android/build_adrenotools.sh")
    if(CW_ADRENOTOOLS_PREFIX)
        if(NOT EXISTS "${CW_ADRENOTOOLS_PREFIX}/include/adrenotools/driver.h")
            message(FATAL_ERROR
                "CW_ADRENOTOOLS_PREFIX=${CW_ADRENOTOOLS_PREFIX} has no "
                "include/adrenotools/driver.h.\n"
                "  tools/android/build_adrenotools.sh ${CW_ADRENOTOOLS_PREFIX}")
        endif()
        find_library(CW_ADRENOTOOLS_LIB NAMES adrenotools
                     PATHS "${CW_ADRENOTOOLS_PREFIX}/lib"
                           "${CW_ADRENOTOOLS_PREFIX}/lib64"
                     NO_DEFAULT_PATH REQUIRED)
        target_compile_definitions(cw_runtime PRIVATE CW_HAVE_ADRENOTOOLS=1)
        target_include_directories(cw_runtime PRIVATE "${CW_ADRENOTOOLS_PREFIX}/include")
        target_link_libraries(cw_runtime PRIVATE "${CW_ADRENOTOOLS_LIB}")
        message(STATUS "adrenotools: ${CW_ADRENOTOOLS_LIB} (custom GPU drivers ON)")
    else()
        message(STATUS "adrenotools: NOT FOUND — custom GPU drivers unavailable, "
                       "system Vulkan driver only")
    endif()
endif()

# The XEX loader. Prebuilt XenonUtils plus the two thirdparty libs it links against.
#
# This is the same loader `tools/xex_image_dump` uses, and it is deliberate that the
# runtime shares it: this XEX is LZX-compressed under the all-zero DEVKIT key, and
# `decrypt_xex.py` cannot read it at all (gotchas 15/16). One loader in the project
# means one thing to keep patched.
set(XENON_BUILD "${XENON_ROOT}/build" CACHE PATH "XenonRecomp build tree")
# The three static libraries, named the way THIS platform names them. Same build
# tree, same directories, different decoration:
#   Linux    libXenonUtils.a
#   Windows  XenonUtils.lib
# CMAKE_STATIC_LIBRARY_{PREFIX,SUFFIX} are what CMake itself uses to construct those,
# so this cannot drift from what the sibling build actually emitted. Hardcoding `.a`
# was the last thing standing between the Windows tree and a successful configure.
set(P "${CMAKE_STATIC_LIBRARY_PREFIX}")
set(S "${CMAKE_STATIC_LIBRARY_SUFFIX}")
foreach(lib
        "${XENON_BUILD}/XenonUtils/${P}XenonUtils${S}"
        "${XENON_BUILD}/thirdparty/fmt/${P}fmt${S}"
        "${XENON_BUILD}/thirdparty/xxHash/cmake_unofficial/${P}xxhash${S}")
    if(NOT EXISTS "${lib}")
        message(FATAL_ERROR
            "Missing ${lib}. Build the sibling XenonRecomp checkout first:\n"
            "  cmake -S ${XENON_ROOT} -B ${XENON_BUILD} && cmake --build ${XENON_BUILD}")
    endif()
    target_link_libraries(cw_runtime PRIVATE "${lib}")
endforeach()

# Whole-archive: the phase 0.2 gate — that EVERY generated symbol resolves — is
# still live, and is now `cw_runtime --smoke`. A normal static-library link pulls in
# only the objects it needs, so an unreferenced TU with an undefined symbol would
# link cleanly and the gate would pass while proving nothing. --whole-archive forces
# every object in. Keep this even once the runtime references most of the image on
# its own: "most" is not "every".
target_link_options(cw_runtime PRIVATE
    "-Wl,--whole-archive" "$<TARGET_FILE:ppc_image>" "-Wl,--no-whole-archive")

# CW_BUNDLE_RPATH — look for the bundled libraries beside the executable first
# (Case Zero release-plan A.4, imported). $ORIGIN is resolved by the loader against
# the executable's own path, so this survives the player unpacking the archive
# anywhere, and it needs no launcher script and no LD_LIBRARY_PATH — a launcher
# script is a thing a player can bypass by running the binary directly, and then
# the bundle silently is not what loads.
#
# Defaults ON for Release only. In a dev tree there is no lib/ beside the binary so
# the entry is never satisfied — harmless, but also an extra variable a measured
# binary should not carry. NOTE (Case Zero A.3's surprise, kept true here): the
# RUNPATH string lives in .dynstr, BEFORE .text, so flipping this option relocates
# the image — hold it matched across any .text-identity comparison.
if(CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT ANDROID)
    set(CW_BUNDLE_RPATH_DEFAULT ON)
else()
    set(CW_BUNDLE_RPATH_DEFAULT OFF)
endif()
option(CW_BUNDLE_RPATH "Add $ORIGIN/lib to the executable's RPATH (release packaging)"
       ${CW_BUNDLE_RPATH_DEFAULT})
# Not WIN32, and not ANDROID either: bionic's dynamic linker ignores DT_RUNPATH, so the
# entry would be dead bytes in .dynstr — and .dynstr sits BEFORE .text, which means dead
# bytes there relocate the image and quietly break a release .text-identity comparison
# against a desktop build. An APK's libraries are all in one directory the linker is
# already told about (nativeLibraryDir), so there is nothing for an rpath to add.
if(CW_BUNDLE_RPATH AND NOT WIN32 AND NOT ANDROID)
    # Literal $ORIGIN, escaped so CMake does not try to expand it as a variable.
    set_target_properties(cw_runtime PROPERTIES
        BUILD_RPATH "\$ORIGIN/lib"
        INSTALL_RPATH "\$ORIGIN/lib")
endif()

if(CW_SPLIT_DEBUG)
    find_program(CW_OBJCOPY NAMES llvm-objcopy objcopy)
    if(NOT CW_OBJCOPY)
        message(FATAL_ERROR
            "CW_SPLIT_DEBUG is on but neither llvm-objcopy nor objcopy was found.\n"
            "  Fedora:  sudo dnf install binutils   (or llvm)\n"
            "Or say -DCW_SPLIT_DEBUG=OFF to ship an unstripped binary on purpose.")
    endif()
    message(STATUS "Release packaging: cw_runtime.debug via ${CW_OBJCOPY}")
    add_custom_command(TARGET cw_runtime POST_BUILD
        COMMAND ${CW_OBJCOPY} --only-keep-debug
                $<TARGET_FILE:cw_runtime> $<TARGET_FILE:cw_runtime>.debug
        COMMAND ${CW_OBJCOPY} --strip-debug $<TARGET_FILE:cw_runtime>
        COMMAND ${CW_OBJCOPY}
                --add-gnu-debuglink=$<TARGET_FILE:cw_runtime>.debug
                $<TARGET_FILE:cw_runtime>
        COMMENT "Splitting debug info into cw_runtime.debug and stripping the binary")
endif()
