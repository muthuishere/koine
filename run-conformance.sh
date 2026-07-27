#!/usr/bin/env bash
# Run the portability conformance checks on every host that is installed.
#
# The point of koine is that ONE source file behaves identically on all of
# them, so this script is the real test suite — `clojure -M:test` only covers
# the JVM.
#
#   clojure  : https://clojure.org/guides/install_clojure
#   cljgo    : go install github.com/muthuishere/cljgo/cmd/cljgo@latest
#   glj      : go install github.com/glojurelang/glojure/cmd/glj@latest
#   lg       : go install github.com/nooga/let-go@latest
set -uo pipefail
cd "$(dirname "$0")/src"

run () {  # name, command...
  local name="$1"; shift
  if ! command -v "${1##* }" >/dev/null 2>&1 && [ ! -x "${1}" ]; then
    printf '%-10s SKIP (not installed)\n' "$name"; return
  fi
  printf '%-10s %s\n' "$name" "$("$@" conformance.cljc 2>&1 | tail -1)"
}

# Every src/*check*.cljc (plus conformance.cljc) is run on every host.
for check in conformance.cljc *_check.cljc; do
  [ -f "$check" ] || continue
  echo "== ${check%.cljc} =="
  printf '%-10s %s\n' "jvm" "$(timeout 60 clojure -Sdeps '{:paths ["."]}' -M "$check" 2>&1 | tail -1)"
  command -v cljgo >/dev/null && printf '%-10s %s\n' "cljgo"   "$(timeout 60 cljgo run "$check" 2>&1 | tail -1)"
  command -v lg    >/dev/null && printf '%-10s %s\n' "let-go"  "$(timeout 60 lg "$check" 2>&1 | tail -1)"
  command -v glj   >/dev/null && printf '%-10s %s\n' "glojure" "$(GLJ_CLASSPATH=. timeout 60 glj "$check" 2>&1 | tail -1)"
done
