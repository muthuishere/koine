#!/usr/bin/env bash
# The let-go example. Same mechanism as the Glojure one: no package manager, so
# koine goes on the load path as the unpacked Clojars jar (source-only, so the
# artifact and the source are the same bytes).
#
# let-go is TIER 3 — it has no byte I/O and no streaming subprocess. The app
# asks koine.host and takes the text/`sh` path instead, which is the point: the
# same source runs, and says which route it took.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.4.2
JAR="$HOME/.m2/repository/net/clojars/muthuishere/koine/$VERSION/koine-$VERSION.jar"
[ -f "$JAR" ] || { echo "fetching koine $VERSION from Clojars"; clojure -Sdeps "{:deps {net.clojars.muthuishere/koine {:mvn/version \"$VERSION\"}}}" -M -e nil >/dev/null; }

rm -rf .koine && mkdir -p .koine && (cd .koine && unzip -qo "$JAR")

exec lg -source-paths ".koine:src:test" "${1:-src/demo/main.cljc}"
