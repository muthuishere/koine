#!/usr/bin/env bash
# Run the portability conformance checks on both supported hosts.
#
# The point of koine is that ONE source file behaves identically on both, so
# this script is the real test suite — `clojure -M:test` only covers the JVM.
#
#   clojure  : https://clojure.org/guides/install_clojure
#   cljgo    : go install github.com/muthuishere/cljgo/cmd/cljgo@latest
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
# stream_check needs a live SSE server; start one if it isn't already up.
if [ -f ../test/sse_server.py ] && [ ! -f /tmp/koine-stream-base ]; then
  python3 ../test/sse_server.py >/tmp/koine-sse.log 2>&1 &
  SSE_PID=$!; trap 'kill $SSE_PID 2>/dev/null' EXIT; sleep 2
fi

for check in conformance.cljc *_check.cljc; do
  [ -f "$check" ] || continue
  echo "== ${check%.cljc} =="
  printf '%-10s %s\n' "jvm" "$(timeout 60 clojure -Sdeps '{:paths ["."]}' -M "$check" 2>&1 | tail -1)"
  command -v cljgo >/dev/null && printf '%-10s %s\n' "cljgo"   "$(timeout 60 cljgo run "$check" 2>&1 | tail -1)"
done
