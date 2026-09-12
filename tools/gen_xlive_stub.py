#!/usr/bin/env python3
"""Generate runtime/kernel/xlive_stub.cpp — the honest no-op XLive surface.

WHY THIS EXISTS
---------------
kernel/xlive_*.cpp is five translation units between this runtime and libxlive
(XenonLive), the account/achievement/session layer. libxlive is a SIBLING CHECKOUT
that is not part of this repository and not public: runtime/CMakeLists.txt requires
XLIVE_ROOT at configure time and stops with a message naming what to clone.

That is the right behaviour for a developer machine and a wrong one for two other
places:

  * CI. No runner has the checkout, so `cmake` fails before it compiles anything —
    a red workflow that says "No XenonLive checkout" and proves nothing about the
    port. This is what .github/workflows/build.yml hits, and what
    .github/workflows/android.yml would hit too.
  * A platform where the layer has no meaning yet. The Android port (milestone A0 in
    docs/android-port-plan.md) ships without it: achievements and co-op sessions are
    not what makes the game playable on a phone, and libxlive's own dependencies are
    one more thing to cross-compile before a single frame is drawn.

So: -DCW_XLIVE=OFF drops the five real sources and compiles this stub instead, and
xlive_glue.h's own promise is what makes that safe — "EVERYTHING HERE IS OPTIONAL AT
RUNTIME. CW_NO_XLIVE=1 turns it off, and with it off every function below behaves as
if no account exists, which is byte-for-byte what this runtime did before." A build
without libxlive is that state, reached at link time instead of at run time.

WHY IT IS GENERATED
-------------------
Forty functions across five headers, and every one of them has to match its
declaration exactly or the link fails with an undefined symbol that names a function
the stub plainly defines. The mirror image is also a link failure and is easier to
miss: a declaration whose definition the KERNEL owns in both configurations must not
be stubbed, or every CW_XLIVE=OFF build fails with `multiple definition of`. Those are
listed in KERNEL_OWNS with their reasons, and the script refuses to write a stub that
collides with any other source in the OFF build. A hand-written stub is a stub that stops matching the day a
header gains a parameter. This script copies each signature VERBATIM from the headers
and the generated file INCLUDES those headers, so the compiler is the gate: a
signature that drifts is a compile error in the stub, not a link error in a build
nobody can reproduce.

Same method and same reasoning as tools/gen_import_stubs.py, which does this for the
244 kernel imports, and tools/gen_stub_ppc.py, which does it for the 58,345 guest
functions CI is not allowed to have.

The return value of each stub is the OFF answer, chosen by type and in the order the
headers themselves document:
    void            nothing
    bool            false      ("not signed in", "not enabled", "not handled")
    integers        0          (no XUID, no handle, no address)
    T*              the parameter of the same type when there is one — the fallback
                    idiom this API uses (`CwXlive_Gamertag(const char* fallback)`) —
                    and nullptr otherwise
    anything else   value-initialised

Regenerate after any change to a kernel/xlive_*.h:

    python3 tools/gen_xlive_stub.py            # rewrites runtime/kernel/xlive_stub.cpp
    python3 tools/gen_xlive_stub.py --check    # CI: fail if the checked-in copy is stale
"""
import argparse
import os
import re
import sys
import textwrap

HEADERS = ["xlive_glue.h", "xlive_session.h", "xlive_social.h", "xlive_stats.h",
           "xlive_net.h"]

# Declarations in those headers whose DEFINITION the kernel owns whether or not libxlive is linked,
# and which this stub must therefore not emit.
#
# Emitting one is invisible at every gate that already exists. The signature cannot drift, because
# it is copied verbatim and the header is included — that is the whole design. --check cannot catch
# it, because --check compares this script's output against itself. It surfaces as
# `multiple definition of` at the link of every CW_XLIVE=OFF build, which is to say in CI, in a job
# that has already spent twenty minutes generating a guest image. It did exactly that.
KERNEL_OWNS = {
    "PostGuestNotification":
        "runtime/kernel/imports.cpp. The notification listeners are kernel objects created by "
        "XamNotifyCreateListener and queued under the kernel lock, so the delivery function is "
        "kernel code; xlive_social.h declares it only because the Live layer posts through it. "
        "imports.cpp is in the build in both configurations, so a definition here is a second one.",
}

# The sources CW_XLIVE=ON compiles instead of the stub. A name defined in one of these is NOT a
# collision, because in an OFF build the file is not in the build; XliveNet_Enabled and
# XliveNet_SelfTest are declared in xlive_net.h and defined in xlive_net.cpp, and the stub is the
# only definition an OFF build has. Every other runtime source is built in both configurations.
XLIVE_ON_SOURCES = frozenset(["xlive_glue.cpp", "xlive_session.cpp", "xlive_social.cpp",
                              "xlive_stats.cpp", "xlive_net.cpp"])

# A definition, as opposed to a declaration or a call: a signature at the start of a line whose
# body opens on the same line or the next one. Good enough to be a tripwire, and it is used only
# to compare against names this script is about to emit.
_DEF_RE = re.compile(r'^[A-Za-z_][\w:<>,\s\*&]*?\b(\w+)\s*\([^;{]*\)\s*\n?\{', re.M)
_KEYWORDS = frozenset(["if", "for", "while", "switch", "return", "catch", "sizeof"])


def names_defined_in(runtime_root, out_name):
    """{function name: [files]} for every runtime source built when CW_XLIVE=OFF."""
    found = {}
    for dirpath, _dirnames, files in os.walk(runtime_root):
        for fn in sorted(files):
            if not fn.endswith(('.cpp', '.c')) or fn == out_name or fn in XLIVE_ON_SOURCES:
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding='utf-8', errors='replace') as f:
                text = strip_comments(f.read())
            for name in _DEF_RE.findall(text):
                if name not in _KEYWORDS:
                    found.setdefault(name, []).append(path)
    return found


def emitted_count(decls_by_header):
    """How many functions the stub will define — declarations minus the ones the kernel owns.

    Reported instead of the declaration count because the two differ, and a script that prints 41
    next to a file whose banner says 40 invites exactly the doubt the number is there to remove."""
    n = 0
    for decls in decls_by_header.values():
        for decl in decls:
            if split_signature(decl)[1] not in KERNEL_OWNS:
                n += 1
    return n


def check_collisions(decls_by_header, runtime_root, out_name):
    """Fail if the stub is about to define something the OFF build already defines."""
    emitted = set()
    for decls in decls_by_header.values():
        for decl in decls:
            name = split_signature(decl)[1]
            if name not in KERNEL_OWNS:
                emitted.add(name)
    defined = names_defined_in(runtime_root, out_name)
    hits = sorted(n for n in emitted if n in defined)
    if not hits:
        return 0
    print('gen_xlive_stub: the stub would define %d function(s) that the CW_XLIVE=OFF build '
          'already defines, which is `multiple definition of` at link time:' % len(hits),
          file=sys.stderr)
    for n in hits:
        print('    %-34s also defined in %s' % (n, ', '.join(sorted(set(defined[n])))),
              file=sys.stderr)
    print('  If the kernel owns it in both configurations, add it to KERNEL_OWNS above with the '
          'reason; if the stub should own it, remove the definition from that source.',
          file=sys.stderr)
    return 1

# Lines that begin a construct which is not a free function declaration. Everything else
# at brace depth 0 that ends in `);` and contains a `(` is a declaration.
SKIP_PREFIXES = ("struct ", "enum ", "class ", "namespace ", "#", "using ", "template",
                 "static_assert", "inline constexpr", "constexpr ", "extern ", "typedef ")

BANNER = """// GENERATED by tools/gen_xlive_stub.py — DO NOT EDIT BY HAND.
// Regenerate:  python3 tools/gen_xlive_stub.py
// Gate:        python3 tools/gen_xlive_stub.py --check   (CI)
//
// The XLive surface with libxlive NOT linked: -DCW_XLIVE=OFF drops kernel/xlive_*.cpp from
// the build and compiles this file instead, so every CwXlive_* and Xlive* call site in the
// runtime resolves to the answer xlive_glue.h documents for "no account exists".
//
// THIS IS A BUILD-TIME STATE, NOT A RUNTIME ONE, and the difference matters: CW_NO_XLIVE=1
// at run time reaches the real implementation and asks it to behave as if signed out, which
// is what every measurement of the offline path was taken against. A CW_XLIVE=OFF build
// never had that implementation, so it cannot be used to reproduce one of those
// measurements — it exists for CI and for platforms where the layer is not ported yet. The
// one line it prints at Start says so out loud, because a build that quietly has no
// achievements is a build whose bug reports will be about achievements.
//
// The signatures below are copied verbatim from the headers, and the headers are included,
// so a declaration that drifts is a compile error HERE rather than an undefined symbol in
// somebody's link. __COUNT__
"""


def strip_comments(text):
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
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def parse_declarations(text):
    """Yield (signature) strings for every free-function declaration at brace depth 0."""
    text = strip_comments(text)
    decls = []
    depth = 0
    current = ''
    for raw in text.split('\n'):
        line = raw.strip()
        if depth == 0 and not current:
            if not line or line.startswith(SKIP_PREFIXES):
                # Track braces even on skipped lines: a struct body has to be walked past
                # rather than scanned for declarations that are not free functions.
                depth += line.count('{') - line.count('}')
                continue
        current = (current + ' ' + line).strip() if current else line
        depth += line.count('{') - line.count('}')
        if current.endswith(';'):
            cand = current[:-1].strip()
            current = ''
            if depth != 0:
                continue
            if '(' not in cand or ')' not in cand:
                continue
            if cand.startswith(SKIP_PREFIXES):
                continue
            # A definition rather than a declaration (has a body) — not present in these
            # headers, but cheap to refuse rather than emit a duplicate.
            if '{' in cand:
                continue
            decls.append(re.sub(r'\s+', ' ', cand))
    return decls


def split_signature(decl):
    """Return (return_type, name, args) for `void CwXlive_Start(uint32_t titleId)`."""
    open_paren = decl.index('(')
    head = decl[:open_paren].strip()
    args = decl[open_paren + 1:decl.rindex(')')].strip()
    parts = head.split()
    if len(parts) < 2:
        raise ValueError('cannot split signature: %r' % decl)
    name = parts[-1]
    ret = ' '.join(parts[:-1])
    return ret, name, args


def default_return(ret, args):
    """The OFF answer for this return type (see the module docstring for the order)."""
    r = ret.strip()
    if r == 'void':
        return None
    if r == 'bool':
        return 'false'
    if r in ('int', 'unsigned', 'long', 'int32_t', 'uint32_t', 'int64_t', 'uint64_t',
             'int16_t', 'uint16_t', 'int8_t', 'uint8_t', 'size_t'):
        # xlive_glue.h: "The account's XUID and gamertag, or the FALLBACK when signed out."
        # An integer parameter named `fallback` is that case, and answering 0 instead would
        # invent a different XUID from the one the caller asked us to use when we have none.
        for arg in [a.strip() for a in args.split(',') if a.strip()]:
            bits = arg.split()
            if len(bits) >= 2 and bits[-1] == 'fallback':
                return 'fallback'
        return '0'
    if r.endswith('*'):
        # The fallback idiom: `const char* F(const char* fallback)` answers with what it
        # was given, which is what the real implementation does when signed out.
        for arg in [a.strip() for a in args.split(',') if a.strip()]:
            bits = arg.split()
            if len(bits) >= 2 and ' '.join(bits[:-1]) == r:
                return bits[-1]
        return 'nullptr'
    return '{}'


def render(decls_by_header):
    out = [BANNER]
    for header in HEADERS:
        out.append('#include "%s"' % header)
    out.append('')
    out.append('#include <cstdio>')
    out.append('')
    total = 0
    skipped = 0
    for header in HEADERS:
        decls = decls_by_header.get(header, [])
        if not decls:
            continue
        out.append('// --- %s %s' % (header, '-' * max(0, 66 - len(header))))
        for decl in decls:
            ret, name, args = split_signature(decl)
            if name in KERNEL_OWNS:
                # The kernel defines this one whether or not libxlive is linked; see KERNEL_OWNS.
                # Wrapped because the reason is the point of the line: a reader who wonders why a
                # declared function has no stub should get the answer here, in the file they are
                # reading, and not have to go and find the script.
                out.extend(textwrap.fill(
                    '%s is NOT stubbed here: %s' % (name, KERNEL_OWNS[name]),
                    width=100, initial_indent='// ', subsequent_indent='// ').split('\n'))
                out.append('')
                skipped += 1
                continue
            total += 1
            arglist = args if args else ''
            out.append('%s %s(%s)' % (ret, name, arglist))
            out.append('{')
            if name == 'CwXlive_Start':
                # Once, loudly, at the only entry point every boot reaches. Adjacent string
                # literals rather than one long line: this message has to survive being read
                # in a log, and it has to survive being generated. The `\\n` at the end is
                # written as a raw string because the generator is one level of quoting away
                # from the C++ it emits, and getting that wrong produces a stray backslash
                # in every log line rather than a compile error.
                out.append('    // Once, loudly, at the only entry point every boot reaches.')
                out.append('    fprintf(stderr,')
                out.append(r'            "[xlive] this build has NO libxlive (-DCW_XLIVE=OFF): no account, "')
                out.append(r'            "no achievements, no session and no leaderboards. Every XLive export "')
                out.append(r'            "answers as if signed out, which is what xlive_glue.h documents for "')
                out.append(r'            "CW_NO_XLIVE=1. See tools/gen_xlive_stub.py.\n");')
                out.append('    (void)titleId;')
            dv = default_return(ret, args)
            if dv is not None:
                out.append('    return %s;' % dv)
            out.append('}')
            out.append('')
    text = '\n'.join(out).rstrip() + '\n'
    note = '\n// %d functions, copied verbatim from the headers above.' % total
    if skipped:
        note += '\n// %d declared but not stubbed, because the kernel defines %s either way.' % (
            skipped, 'it' if skipped == 1 else 'them')
    return text.replace('__COUNT__', note)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--kernel', default='runtime/kernel',
                    help='directory holding the xlive_*.h headers')
    ap.add_argument('--out', default='xlive_stub.cpp',
                    help='file to write inside --kernel')
    ap.add_argument('--check', action='store_true',
                    help='do not write; exit non-zero if the checked-in file differs')
    args = ap.parse_args()

    decls_by_header = {}
    for header in HEADERS:
        path = os.path.join(args.kernel, header)
        if not os.path.exists(path):
            print('gen_xlive_stub: no %s — is --kernel %s the runtime\'s kernel directory?'
                  % (path, args.kernel), file=sys.stderr)
            return 1
        with open(path, encoding='utf-8') as f:
            decls_by_header[header] = parse_declarations(f.read())

    # Before writing anything: the collision that this stub cannot detect about itself. Run in both
    # modes, because a stub that is CURRENT and colliding is the state CI was in.
    runtime_root = os.path.dirname(os.path.abspath(args.kernel))
    if check_collisions(decls_by_header, runtime_root, args.out):
        return 1

    text = render(decls_by_header)
    out = os.path.join(args.kernel, args.out)

    if args.check:
        old = ''
        if os.path.exists(out):
            with open(out, encoding='utf-8') as f:
                old = f.read()
        if old == text:
            print('gen_xlive_stub: %s is current (%d functions)'
                  % (args.out, emitted_count(decls_by_header)))
            return 0
        print('gen_xlive_stub: %s IS STALE. Run:\n    python3 tools/gen_xlive_stub.py\n'
              'A stale stub is an undefined symbol at link time in every CW_XLIVE=OFF '
              'build, i.e. in CI.' % args.out, file=sys.stderr)
        return 1

    with open(out, 'w', encoding='utf-8') as f:
        f.write(text)
    print('gen_xlive_stub: wrote %s (%d functions)' % (out, emitted_count(decls_by_header)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
