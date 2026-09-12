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
# in a compiler's output lives. So the tail is joined into a SINGLE message with %0A, the annotation
# encoding for a newline, and one annotation is spent saying all of it.
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
    msg=$(tail -n 40 "$LOG" |
        awk '{gsub(/%/, "%25"); gsub(/\r/, "%0D"); printf "%s%s", (NR > 1 ? "%0A" : ""), $0}')
    echo "::error::$* exited $rc. The last 40 lines of $LOG follow.%0A%0A$msg"
fi

exit "$rc"
