#!/usr/bin/env bash
# Run a command, keep its output in a log file AND on the console, and if it fails publish the tail
# of that log as one ::error:: annotation.
#
#     tools/ci/run_logged.sh <logfile> <command> [args...]
#
# Environment assignments may precede the call as usual; they are inherited by the command:
#
#     CW_ROOT="$PWD" tools/ci/run_logged.sh shader-cache.log runtime/build-host/cw_runtime --flag
#
# WHY THIS EXISTS
#
# GitHub serves three things about a run through three different channels, and only two of them are
# reachable from everywhere. Step VERDICTS and ANNOTATIONS come from the REST API. Step LOGS and job
# ARTIFACTS come from blob storage behind a signed URL. This repository is debugged through the
# public API, so a failing step that emits no annotation is a red square and nothing else: no text,
# on any channel that works, saying why.
#
# That is not hypothetical. The playable job's first failure at "build the host XenonRecomp" was
# reported, in full, as "Process completed with exit code 1." The artifact that job uploads is
# assembled from log FILES, the step wrote none, and `if: always()` faithfully uploaded nothing.
# There was no way to read the error except by guessing at it from the YAML.
#
# WHY ONE ANNOTATION AND NOT ONE PER LINE
#
# GitHub keeps at most ten error annotations per step. The obvious loop —
#
#     tail -n 40 "$LOG" | while IFS= read -r line; do echo "::error::$line"; done
#
# is therefore self-defeating: it discards thirty of the forty lines it was written to preserve, and
# the discard is not the tail, it is everything after line ten, which is where the first diagnostic
# in a compiler's output lives. So the extract is joined into a SINGLE message with %0A, the
# annotation encoding for a newline, and one annotation is spent saying all of it.
#
# The percent escaping has to happen before the join, not after, because the escaping itself
# introduces percents: `%` -> `%25` first, then carriage returns, then the newlines that separate
# the lines.

set -uo pipefail

if [ "$#" -lt 2 ]; then
    echo "usage: run_logged.sh <logfile> <command> [args...]" >&2
    exit 2
fi

LOG="$1"
shift

# A separator per invocation, because one log file is shared by every command in a step and an
# undivided concatenation of two cmake runs is hard to read at the point where the second one
# starts failing on something the first one did.
printf '\n$ %s\n' "$*" >>"$LOG"

# tee rather than a redirect: the person watching the run live should see the build, and the log
# file is for after the fact. PIPESTATUS[0] is the command's status, not tee's — GitHub's default
# shell is `bash -e` WITHOUT pipefail, so a pipeline's own status is its last stage's, and tee
# always succeeds. Captured immediately, because every subsequent command rewrites the array.
"$@" 2>&1 | tee -a "$LOG"
rc=${PIPESTATUS[0]}

if [ "$rc" -ne 0 ]; then
    # TWO extracts, because a parallel build does not put the cause at the end. ninja -j4
    # interleaves, so the FAILED block for the target that broke can sit hundreds of lines above the
    # last line printed; and a link command is a SINGLE line long enough to consume the whole
    # annotation by itself. That is what happened to the first link failure this wrapper caught: the
    # message ended in the middle of the command line and the undefined symbols printed after it
    # were never sent, so the annotation cost a run and said nothing. So — every line that looks
    # like a diagnostic, from anywhere in the log, then the tail; and every line CLIPPED, because
    # forty short lines carry more information than one long one.
    # The pattern list has to cover the tools this pipeline actually runs, and it did not. A run that
    # failed inside Gradle published an annotation whose diagnostic section was EMPTY and whose tail
    # was twenty-five Java stack frames, because a Kotlin error is spelled
    # `e: file:///...GameActivity.kt:244:22 Unresolved reference 'editMode'` and a CMake configure
    # failure is spelled `CMake Error at CMakeLists.txt:341 (message):`, and neither contains
    # `error:`. Both shapes are here now, with the three lines Gradle uses to introduce a failure.
    diag=$(grep -n -E 'error:|Error [0-9]+|undefined reference|undefined symbol|FAILED:|cannot find|ld(\.lld)?:|fatal|No such file|^e: |Unresolved reference|CMake Error|FAILURE:|BUILD FAILED|What went wrong|Execution failed for task' \
        "$LOG" 2>/dev/null | tail -n 20 | cut -c1-300 || true)
    # The pipe is INSIDE the command substitution, which is not a stylistic detail. Written as
    # `msg=$( ... ) | awk ...`, the left side of a pipeline runs in a subshell, so msg is assigned
    # there and is unset here — and under `set -u` the script then dies on the next line, without
    # printing the annotation it exists to print. That version passed `bash -n`, was written by
    # somebody who knew what pipefail does to a pipeline, and was caught only by running it.
    msg=$(
        {
            if [ -n "$diag" ]; then
                printf 'lines matching a diagnostic pattern, from anywhere in the log:\n%s\n\nlast 25 lines, Java and Gradle stack frames removed:\n' "$diag"
            fi
            # Java and Gradle stack frames are the one kind of noise that reliably fills a tail whole:
            # `at org.gradle.internal.operations...` twenty-five times over is a tail carrying no
            # information, and it is what the last run published. Filtered from a longer window so
            # that removing the frames still leaves twenty-five real lines. The `|| true` is not
            # decoration: grep -v exits 1 when it filters everything away, and under pipefail that
            # would fail the substitution and cost the annotation this script exists to publish.
            { tail -n 250 "$LOG" | grep -v -E '^[[:space:]]*(at |\.\.\. [0-9]+ more$)' || true; } |
                tail -n 25 | cut -c1-300
        } | awk '{gsub(/%/, "%25"); gsub(/\r/, "%0D"); printf "%s%s", (NR > 1 ? "%0A" : ""), $0}'
    )
    echo "::error::$* exited $rc. Diagnostics and tail of $LOG follow.%0A%0A$msg"
fi

exit "$rc"
