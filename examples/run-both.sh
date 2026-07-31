#!/usr/bin/env bash
# Run the SAME app and the SAME test suite on both supported hosts, then diff
# what they printed. A pass means: identical source, identical tests, identical
# output, koine resolved from Clojars by both.
#
#   ./examples/run-both.sh
set -uo pipefail
cd "$(dirname "$0")"

fail=0
say () { printf '\n\033[1m== %s\033[0m\n' "$1"; }

say "Clojure (JVM) — tests"
( cd clojure-app && clojure -M:test 2>&1 | tail -3 ) || fail=1

say "cljgo — tests"
# `cljgo test` needs the dependency resolved, which `cljgo build` does.
( cd cljgo-app && cljgo build >/dev/null 2>&1 && cljgo test 2>&1 | tail -3 ) || fail=1

say "Clojure (JVM) — app output"
jvm=$( cd clojure-app && DEMO_WORKDIR=/tmp/koine-demo-jvm clojure -M -m demo.app 2>/dev/null | tail -1 )
echo "$jvm"

say "cljgo — app output (native binary)"
go=$( cd cljgo-app && DEMO_WORKDIR=/tmp/koine-demo-go ./demo 2>/dev/null | tail -1 )
echo "$go"

say "diff"
# The two differ only where they MUST: the workdir (set per host above so they
# cannot clobber each other), the ISO timestamp, and the measured duration.
# Everything else — the JSON shape, the key order, the base64, the byte count,
# the two-turn subprocess conversation — has to match exactly.
norm () { sed -E -e 's#/tmp/koine-demo-[a-z]+#WORKDIR#g' \
                 -e 's#"at":"[^"]*"#"at":"AT"#' \
                 -e 's#"echo-ms":[0-9]+#"echo-ms":MS#' <<<"$1"; }
if [ "$(norm "$jvm")" = "$(norm "$go")" ]; then
  printf '\033[32midentical\033[0m (modulo workdir, timestamp, duration)\n'
else
  printf '\033[31mDIVERGED\033[0m\n'
  diff <(norm "$jvm") <(norm "$go") || true
  fail=1
fi

exit $fail
