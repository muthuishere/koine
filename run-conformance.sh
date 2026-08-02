#!/usr/bin/env bash
# Run the portability conformance checks on both supported hosts.
#
# The point of koine is that ONE source file behaves identically on both, so
# this script is the real test suite — `clojure -M:test` only covers the JVM.
#
#   clojure  : https://clojure.org/guides/install_clojure
#   cljgo    : go install github.com/muthuishere/cljgo/cmd/cljgo@latest
#
# It prints WHAT IT RAN AGAINST before it runs anything, and exits non-zero if
# any check fails — so it can gate a release instead of being read by a human.
# CHANGELOG.md requires the measured host versions for every release; this is
# where that line comes from, so it cannot be filled in from memory.
set -uo pipefail
cd "$(dirname "$0")/src"

# --------------------------------------------------------------- provenance
#
# `cljgo version` prints whatever version string the SOURCE it was built from
# happens to carry, which is not evidence about any release. On this machine the
# first `cljgo` on PATH was a 422-byte shell shim that rebuilds from a local
# working tree on every invocation while reporting a release-shaped "0.8.5" —
# and a run against a 20-commit-old tree produced two claims that had to be
# retracted. Go records the truth in the binary: a `go install …@vX.Y.Z` build
# carries a `mod` line with a checksum and NO `vcs` stanza; a build from a
# checkout carries `vcs=git`. Ask the binary, never the version string.
cljgo_provenance () {
  local bin; bin="$(command -v cljgo 2>/dev/null)" || { echo "not installed"; return; }
  local info mod vcs
  info="$(go version -m "$bin" 2>/dev/null)"
  mod="$(printf '%s\n' "$info" | awk '$1=="mod"{print $3; exit}')"
  if [ -z "$mod" ]; then
    # No module info: either not a Go binary at all, or no Go toolchain to ask.
    # LC_ALL=C matters — in a UTF-8 locale grep will not match a class against
    # bytes that are not valid UTF-8, which is exactly what a Mach-O header is.
    if LC_ALL=C head -c 2 "$bin" 2>/dev/null | LC_ALL=C grep -q '^#!'; then
      local src; src="$(grep -m1 '^SRC=' "$bin" 2>/dev/null | cut -d'"' -f2)"
      echo "SOURCE TREE via wrapper script — NOT a release${src:+ (${src})}"
    elif ! command -v go >/dev/null 2>&1; then
      echo "unknown — no Go toolchain to read the build info — NOT a release"
    else
      echo "unknown — no Go build info — NOT a release"
    fi
    return
  fi
  vcs="$(printf '%s\n' "$info" | awk '$1=="build" && $2 ~ /^vcs=/{print; exit}')"
  if [ -n "$vcs" ] || [ "$mod" = "(devel)" ]; then
    echo "${mod} built from a CHECKOUT — NOT a release"
  else
    echo "${mod} (released build)"
  fi
}

CLJGO_PROV="$(cljgo_provenance)"
echo "== what this run measures =="
printf '%-10s %s\n' "clojure" "$(clojure -Sdescribe 2>/dev/null | awk -F'"' '/:version/{print $2; exit}')"
printf '%-10s %s\n' "cljgo"   "$CLJGO_PROV"
printf '%-10s %s\n' "CLJGO_SRC" "${CLJGO_SRC:-unset}"
case "$CLJGO_PROV" in
  *"NOT a release"*)
    echo
    echo "  !! NOT RELEASE EVIDENCE. This cljgo is a working tree, so a green run"
    echo "     here says nothing about any published cljgo version and must not be"
    echo "     written into CHANGELOG.md. For that, install a tag and re-run:"
    echo "       go install github.com/muthuishere/cljgo/cmd/cljgo@vX.Y.Z"
    echo "       env -u CLJGO_SRC PATH=\"\$HOME/go/bin:\$PATH\" ./run-conformance.sh"
    ;;
esac
echo

# Every src/*check*.cljc (plus conformance.cljc) is run on every host.
# stream_check needs a live SSE server; start one if it isn't already up.
if [ -f ../test/sse_server.py ] && [ ! -f /tmp/koine-stream-base ]; then
  python3 ../test/sse_server.py >/tmp/koine-sse.log 2>&1 &
  SSE_PID=$!; trap 'kill $SSE_PID 2>/dev/null' EXIT; sleep 2
fi

# A check's last line is "<passed>/<total> pass". Anything else — a crash, a
# timeout, an empty run — is a FAILURE, not a skip: cljgo once reported
# "Ran 0 tests … 0 failures" for a walk that never found the files, and a gate
# that reads green when it ran nothing is worse than no gate.
FAILED=0
report () {  # host, last-line
  local host="$1" line="$2" pass total
  printf '%-10s %s\n' "$host" "$line"
  pass="$(printf '%s' "$line" | sed -n 's:^\([0-9][0-9]*\)/\([0-9][0-9]*\) pass$:\1:p')"
  total="$(printf '%s' "$line" | sed -n 's:^\([0-9][0-9]*\)/\([0-9][0-9]*\) pass$:\2:p')"
  if [ -z "$pass" ] || [ -z "$total" ] || [ "$pass" != "$total" ] || [ "$total" = "0" ]; then
    FAILED=$((FAILED + 1))
  fi
}

for check in conformance.cljc *_check.cljc; do
  [ -f "$check" ] || continue
  echo "== ${check%.cljc} =="
  report "jvm" "$(timeout 60 clojure -Sdeps '{:paths ["."]}' -M "$check" 2>&1 | tail -1)"
  if command -v cljgo >/dev/null; then
    report "cljgo" "$(timeout 60 cljgo run "$check" 2>&1 | tail -1)"
  else
    printf '%-10s %s\n' "cljgo" "SKIP (not installed)"
  fi
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "conformance GREEN — clojure $(clojure -Sdescribe 2>/dev/null | awk -F'"' '/:version/{print $2; exit}') · cljgo ${CLJGO_PROV}"
  case "$CLJGO_PROV" in
    *"NOT a release"*) echo "(still not release evidence — see the warning above)"; exit 0 ;;
  esac
  exit 0
else
  echo "conformance RED — ${FAILED} host-check(s) failed or did not report"
  exit 1
fi
