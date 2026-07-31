#!/usr/bin/env bash
# The Glojure example.
#
# Glojure has no package manager of its own: GLJ_CLASSPATH is the whole
# mechanism, so koine is put on it as SOURCE. The jar published to Clojars is
# source-only (no AOT, no Java), so "the artifact" and "the source tree" are the
# same bytes — this unpacks the very jar the other two projects resolve, rather
# than pointing at ../../src, so it is a real consumer and not an inside job.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.4.1
JAR="$HOME/.m2/repository/net/clojars/muthuishere/koine/$VERSION/koine-$VERSION.jar"
[ -f "$JAR" ] || { echo "fetching koine $VERSION from Clojars"; clojure -Sdeps "{:deps {net.clojars.muthuishere/koine {:mvn/version \"$VERSION\"}}}" -M -e nil >/dev/null; }

rm -rf .koine && mkdir -p .koine && (cd .koine && unzip -qo "$JAR")

GLJ_CLASSPATH=".koine:src:test" exec glj "${1:-src/demo/main.cljg}"
