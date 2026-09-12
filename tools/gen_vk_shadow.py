#!/usr/bin/env python3
"""Generate runtime/gpu/vk_shadow_android.h — the Android Vulkan entry-point shadow.

WHY THIS EXISTS (docs/android-port-plan.md §5, "the driver is not the system one")
---------------------------------------------------------------------------------
Every other platform in this runtime calls Vulkan the ordinary way: link
`Vulkan::Vulkan` and call `vkCmdBlitImage(...)`. The symbol resolves against the
loader the OS installed, which then talks to the driver the OS installed.

Android breaks the second half of that sentence. The whole reason a phone port of
this game is playable at all is Turnip — Mesa's Adreno driver, newer than the one
in /vendor, loaded from the app's own storage with no root. There are exactly two
supported ways to get it (both used by shipping emulators):

  1. libadrenotools (bylaws, BSD-2-Clause): loads /system/lib64/libvulkan.so into a
     private linker namespace with a hook that redirects the loader's own
     `dlopen("/vendor/lib64/hw/vulkan.adreno.so")` at a driver file WE supply, and
     hands back a `void*` to that hooked loader. Every Vulkan call must then go
     through that handle — a second, differently-loaded copy of libvulkan is a
     second loader with a second driver table, i.e. the system driver.
  2. Nothing else. `VK_ICD_FILENAMES`/`VK_DRIVER_FILES` are honoured by Android's
     loader only for debuggable apps, and a release APK is not one.

So the runtime cannot link libvulkan on Android: the linker would bind
`vkCmdBlitImage` to the system loader at load time and no later decision could
change it. What it CAN do is what this project already does for `mftb`: shadow the
name. cpu/timebase.h turns every `__rdtsc()` in the recompiled image into a scaled
guest counter by force-including a header that #defines it. This is the same trick
one layer up — `vkFoo` becomes a slot in a table we fill from adrenotools' handle,
and the 100-odd call sites in gpu/vk_renderer.cpp do not change at all.

WHY IT IS GENERATED, AND FROM A SCAN
------------------------------------
A hand-written list of Vulkan functions is a list that is wrong the day someone
adds a call. This script scans the runtime's own built sources for call sites —
the same method tools/gen_stub_ppc.py uses to find the guest functions the host
references — so the table is complete by construction and minimal by the same
token: a function nobody calls is not in it, and therefore is not required to
exist in the loader (which matters, see the two-phase fill below).

String literals are stripped before scanning, deliberately. vk_renderer.cpp
resolves its extension entry points by NAME (`dp("vkCreateAccelerationStructureKHR")`)
into local `pfn*` variables, and those names must NOT be shadowed: the loader does
not export them as global symbols on any platform, so putting them in the table
would turn a working dynamic resolve into a null pointer.

THE TWO-PHASE FILL, which is why this is not just a dlsym loop
--------------------------------------------------------------
Android's libvulkan exports the core entry points *of the version that Android
release shipped*: 1.1 on Android 9, 1.3 from Android 13. This runtime is Vulkan 1.3
(README, Requirements) and Turnip is 1.3+, but on an Android 11 phone the LOADER is
still 1.1 — `dlsym(handle, "vkCmdBeginRendering")` returns null there even though the
driver implements it. Phase 1 dlsyms everything; phase 2 re-resolves whatever came
back null through `vkGetInstanceProcAddr(instance, name)`, which is the loader's own
documented answer for entry points it does not export. So the shadow is strictly
more portable than the direct link it replaces — the desktop build would fail to
LOAD on an Android-11-era loader, and this one runs.

Regenerate after any change to a Vulkan call site:

    python3 tools/gen_vk_shadow.py            # rewrites runtime/gpu/vk_shadow_android.h
    python3 tools/gen_vk_shadow.py --check    # CI: fail if the checked-in copy is stale

`--check` is the gate. A stale shadow is not a compile error — it is an undefined
symbol at link time on Android only, which is the one platform CI has to work
hardest to prove anything about.
"""
import argparse
import os
import re
import sys

# The sources scanned: everything the runtime builds that can reach Vulkan. Kept as a
# list of directories plus explicit extras rather than "all of runtime/" so that
# runtime/port-pending/ — parked sources that are NOT in the build (see
# runtime/CMakeLists.txt's comment on them) — cannot add a name to the table that no
# built object ever calls.
SCAN_DIRS = ["runtime/gpu", "runtime/host", "runtime/cpu", "runtime/kernel",
             "runtime/audio", "runtime"]
SCAN_EXTRA = []          # e.g. the XenosRecomp translator, if it ever calls Vulkan

CALL_RE = re.compile(r"\b(vk[A-Z][A-Za-z0-9_]*)\s*\(")
# Names that are prefixes of others in the source but never functions of their own
# (`vkCmd` from "vkCmd*" in a comment, `vkStencil...` from a struct field). A call site
# always has a '(' after it, so the regex already excludes most of these; this is the
# belt-and-braces list for identifiers that DO appear before a paren in prose.
BLOCKLIST = {"vkCmd", "vkClear", "vkStencil"}

HEADER = """#pragma once
// AUTO-GENERATED by tools/gen_vk_shadow.py — DO NOT EDIT BY HAND.
// Regenerate:  python3 tools/gen_vk_shadow.py
// Gate:        python3 tools/gen_vk_shadow.py --check   (CI, .github/workflows/android.yml)
//
// WHAT THIS FILE IS: every Vulkan entry point this runtime calls, as a slot in a table
// that gpu/vk_shadow_android.cpp fills from the loader handle libadrenotools returned —
// plus the #define shadows that bind the existing call sites to those slots without
// touching them. Read the generator's docstring for why Android cannot simply link
// libvulkan, and why the fill happens in two phases.
//
// INCLUDE ORDER IS LOAD-BEARING, exactly as it is in cpu/timebase.h: this header
// includes <vulkan/vulkan.h> FIRST, so the include guard is already set when a
// translation unit that includes both reaches its own <vulkan/vulkan.h> line. Were the
// macros below in scope while the compiler read vulkan_core.h, every declaration inside
// it would be rewritten (`VKAPI_ATTR VkResult VKAPI_CALL CwVk::g_table.vkCreateInstance(...)`)
// and the build would die inside a Khronos header naming neither this file nor Android.
//
// NON-ANDROID BUILDS COMPILE THIS TO NOTHING. The whole file is one #if, so the desktop
// runtime keeps linking Vulkan::Vulkan and every measurement ever taken on it stays
// about the same binary.

#if defined(__ANDROID__)

#include <vulkan/vulkan.h>

namespace CwVk
{

// One X-macro list, used three times: to declare the table, to fill it, and to report
// on it. Three lists that have to agree is a defect waiting for the fourth call site.
#define CWVK_FUNCTION_LIST(X) \\
__LIST__

struct Table
{
#define CWVK_X(fn) PFN_##fn fn = nullptr;
    CWVK_FUNCTION_LIST(CWVK_X)
#undef CWVK_X
};

// How many entry points the list holds. The loader's log line says "N of M resolved", and
// an M that is counted by hand is an M that is wrong the first time someone adds a call —
// which is the exact defect the scan exists to prevent. Straight-line increments are a
// valid constant expression, so this costs nothing at run time.
inline constexpr int FunctionCount()
{
    int n = 0;
#define CWVK_X(fn) ++n;
    CWVK_FUNCTION_LIST(CWVK_X)
#undef CWVK_X
    return n;
}

extern Table g_table;

// Phase 1, before anything creates an instance: dlsym every name out of `loader`
// (adrenotools' hooked libvulkan, or the plain system one when no custom driver is
// selected). Returns the count that resolved; the rest stay null for phase 2.
int InitFromLoader(void* loader);

// Phase 2, once an instance exists: fill whatever phase 1 left null through
// vkGetInstanceProcAddr. Safe to call more than once (a device-loss rebuild creates a
// second instance) and cheap when nothing is missing.
void InitFromInstance(VkInstance instance);

// vkCreateInstance is the ONE entry point that cannot be a bare table slot: it is the
// call that produces the instance phase 2 needs, so it is a hand-written wrapper that
// forwards and then fills. The shadow below points at it, not at the table.
VkResult CreateInstance(const VkInstanceCreateInfo* pCreateInfo,
                        const VkAllocationCallbacks* pAllocator, VkInstance* pInstance);

// One line per unresolved entry point, for the log and for `--diag`. A phone that
// cannot render should say WHICH function is missing, not just that it cannot.
int ReportMissing();

// True once InitFromLoader has run. Every consumer checks this rather than assuming:
// "no loader" and "a loader with a missing entry point" are different failures and
// this runtime has paid for confusing them before (gotcha 5: fail loudly, name it).
bool Ready();

// THE ANDROID HALF, and the only entry point the rest of the runtime needs. Acquires a
// loader — libadrenotools' hooked one when the player selected a custom GPU driver, the
// plain system libvulkan.so otherwise — and fills the table from it. Idempotent, because
// both the bridge and the window module have reasons to want it up before the renderer
// asks, and a second acquire would load a second loader with a second driver table.
// Returns false only when NO loader could be opened, which is "this device has no Vulkan"
// and not "the driver the player picked is broken" — the two are reported separately.
bool InitLoader();

// What InitLoader ended up using: "adrenotools:<libraryName>", "system" or "none". One
// line in the boot log, because a phone that renders with the vendor driver when the
// player believes they selected Turnip is a phone whose performance report is about the
// wrong driver (gotcha 5's other half: say which arm you are in).
const char* LoaderSource();

}  // namespace CwVk

// --- the shadows ---------------------------------------------------------------
// Literal lines, not an X-macro expansion: the preprocessor cannot emit a #define from
// inside a macro, which is the only reason this block is generated rather than written
// once. Sorted, so a diff between two regenerations shows exactly what changed.
//
// CWVK_NO_SHADOW opts out of the macros and keeps the table. Two translation units need
// that: this module's own implementation (which fills the table and must therefore be
// able to name the real functions), and the exported SDL-facing entry points at the
// bottom of it — SDL_Vulkan_LoadLibrary dlsyms `vkGetInstanceProcAddr`, `vkCreateInstance`
// and `vkEnumerateInstance*` BY NAME out of whatever library it is pointed at, so those
// names have to exist as exported symbols here, which a #define would rename away.
#ifndef CWVK_NO_SHADOW
__SHADOWS__
#endif  // CWVK_NO_SHADOW

#endif  // __ANDROID__
"""

CREATE_INSTANCE_SHADOW = "#define vkCreateInstance CwVk::CreateInstance"


def strip_noise(text):
    """Remove comments and string/char literals so prose and dp("vkName") cannot add a
    function to the table."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                i += 1
            i += 2
        elif c in '"\'':
            quote = c
            i += 1
            while i < n and text[i] != quote:
                i += 2 if text[i] == '\\' else 1
            i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def scan(root):
    names = set()
    paths = []
    for d in SCAN_DIRS:
        base = os.path.join(root, d)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if name.endswith(('.cpp', '.h', '.inc')):
                paths.append(os.path.join(base, name))
    paths += [os.path.join(root, p) for p in SCAN_EXTRA]

    for path in paths:
        # The shadow's own implementation file uses the real names on purpose (it is
        # compiled with CWVK_NO_SHADOW); scanning it would be circular.
        if os.path.basename(path).startswith('vk_shadow_android'):
            continue
        try:
            with open(path, encoding='utf-8', errors='replace') as f:
                text = f.read()
        except OSError:
            continue
        for m in CALL_RE.finditer(strip_noise(text)):
            name = m.group(1)
            if name in BLOCKLIST:
                continue
            names.add(name)
    return sorted(names)


def render(names):
    list_body = ' \\\n'.join('    X(%s)' % n for n in names)
    shadows = '\n'.join(
        CREATE_INSTANCE_SHADOW if n == 'vkCreateInstance'
        else '#define %s CwVk::g_table.%s' % (n, n)
        for n in names)
    return HEADER.replace('__LIST__', list_body).replace('__SHADOWS__', shadows)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--repo', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help='repository root (default: the one this script lives in)')
    ap.add_argument('--out', default='runtime/gpu/vk_shadow_android.h',
                    help='file to write, relative to --repo')
    ap.add_argument('--check', action='store_true',
                    help='do not write; exit non-zero if the checked-in file differs')
    ap.add_argument('--print', dest='print_only', action='store_true',
                    help='print the scanned names and exit')
    args = ap.parse_args()

    names = scan(args.repo)
    if not names:
        print('gen_vk_shadow: scanned nothing — is --repo %s the repository root?'
              % args.repo, file=sys.stderr)
        return 1
    if args.print_only:
        print('\n'.join(names))
        return 0

    text = render(names)
    out = os.path.join(args.repo, args.out)

    if args.check:
        old = ''
        if os.path.exists(out):
            with open(out, encoding='utf-8') as f:
                old = f.read()
        if old == text:
            print('gen_vk_shadow: %s is current (%d entry points)' % (args.out, len(names)))
            return 0
        print('gen_vk_shadow: %s IS STALE — %d entry points scanned. Run:\n'
              '    python3 tools/gen_vk_shadow.py\n'
              'A stale shadow is an undefined symbol at link time on Android only.'
              % (args.out, len(names)), file=sys.stderr)
        missing = sorted(n for n in names if ('X(%s)' % n) not in old)
        extra = sorted(set(re.findall(r'X\((vk\w+)\)', old)) - set(names))
        if missing:
            print('  new call sites: %s' % ', '.join(missing), file=sys.stderr)
        if extra:
            print('  no longer called: %s' % ', '.join(extra), file=sys.stderr)
        return 1

    with open(out, 'w', encoding='utf-8') as f:
        f.write(text)
    print('gen_vk_shadow: wrote %s (%d entry points)' % (args.out, len(names)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
