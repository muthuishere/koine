#!/usr/bin/env bash
# Run the SAME app and the SAME test suite on every installed host, then diff
# what they printed. A pass means: identical source, identical tests, identical
# output, koine resolved from Clojars by all of them.
#
#   ./examples/run-both.sh
#
# The two SUPPORTED hosts (JVM, cljgo) must agree byte for byte. Glojure and
# let-go are tier 2/3: they are run and reported, and let-go legitimately
# differs — it has no byte I/O and no streaming child, so the app takes its
# text/`sh` fallback and says so in `binary?` / `streamed?`.
set -uo pipefail
cd "$(dirname "$0")"

fail=0
say () { printf '\n\033[1m== %s\033[0m\n' "$1"; }
have () { command -v "$1" >/dev/null 2>&1; }

say "Clojure (JVM) - tests"
( cd clojure-app && clojure -M:test 2>&1 | tail -3 ) || fail=1

say "cljgo - tests"
# `cljgo test` needs the dependency resolved, which `cljgo build` does.
( cd cljgo-app && cljgo build >/dev/null 2>&1 && cljgo test 2>&1 | tail -3 ) || fail=1

if have glj; then
  say "Glojure - tests"
  ( cd glojure-app && ./run.sh 2>&1 | tail -3 ) || fail=1
else
  say "Glojure - SKIPPED (glj not installed)"
fi

if have lg; then
  say "let-go - tests"
  ( cd let-go-app && ./run.sh 2>&1 | tail -2 ) || fail=1
else
  say "let-go - SKIPPED (lg not installed)"
fi

say "app output, per host"
jvm=$( cd clojure-app && DEMO_WORKDIR=/tmp/koine-demo-jvm clojure -M -m demo.app 2>/dev/null | tail -1 )
go=$(  cd cljgo-app   && DEMO_WORKDIR=/tmp/koine-demo-go  ./demo               2>/dev/null | tail -1 )
printf 'jvm   %s\n\n' "$jvm"
printf 'cljgo %s\n' "$go"
if have glj; then
  glj=$( cd glojure-app && DEMO_WORKDIR=/tmp/koine-demo-glj ./run.sh 2>/dev/null | grep '^{' | head -1 )
  printf '\nglj   %s\n' "$glj"
fi
if have lg; then
  lgo=$( cd let-go-app && DEMO_WORKDIR=/tmp/koine-demo-lg ./run.sh 2>/dev/null | grep '^{' | head -1 )
  printf '\nlg    %s\n' "$lgo"
fi

say "diff (the two SUPPORTED hosts must match)"
# They differ only where they MUST: the workdir (set per host above so they
# cannot clobber each other), the ISO timestamp, the measured duration, and the
# host's own name. Everything else — the JSON shape, the key order, the base64,
# the byte count, the two-turn subprocess conversation — has to match exactly.
norm () { sed -E -e 's#/tmp/koine-demo-[a-z]+#WORKDIR#g' \
                 -e 's#"at":"[^"]*"#"at":"AT"#' \
                 -e 's#"echo-ms":[0-9]+#"echo-ms":MS#' \
                 -e 's#"host":"[a-z-]+"#"host":"HOST"#' \
                 -e 's#"tier":"[a-z-]+"#"tier":"TIER"#' <<<"$1"; }
if [ "$(norm "$jvm")" = "$(norm "$go")" ]; then
  printf '\033[32mjvm == cljgo\033[0m (modulo workdir, timestamp, duration, host name)\n'
else
  printf '\033[31mDIVERGED\033[0m\n'
  diff <(norm "$jvm") <(norm "$go") || true
  fail=1
fi

if have glj; then
  if [ "$(norm "$jvm")" = "$(norm "$glj")" ]; then
    printf '\033[32mjvm == glojure\033[0m\n'
  else
    printf '\033[33mglojure differs\033[0m (tier 2 — reported, does not gate)\n'
    diff <(norm "$jvm") <(norm "$glj") || true
  fi
fi

if have lg; then
  # let-go is EXPECTED to differ, in exactly two documented ways.
  if grep -q '"binary?":false' <<<"$lgo" && grep -q '"streamed?":false' <<<"$lgo"; then
    printf '\033[33mlet-go took the documented fallbacks\033[0m (binary?=false, streamed?=false — no byte I/O, no streaming child)\n'
  else
    printf '\033[31mlet-go output unexpected\033[0m\n'
    fail=1
  fi
fi

exit $fail
