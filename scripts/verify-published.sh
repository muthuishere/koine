#!/usr/bin/env bash
# Verify that the jar CLOJARS ACTUALLY SERVES is the one this repo tagged.
#
#   ./scripts/verify-published.sh            # the version in build.clj
#   ./scripts/verify-published.sh 0.10.0
#
# Asked for by the toolnexus Clojure port, which caught a lock/registry mismatch
# with the equivalent check on its side — a jar in ~/.m2 that was a LOCAL
# install, so a lock generated against it pinned a hash the registry does not
# serve. They said it had already broken their CI once before they built it, and
# koine had nothing equivalent.
#
# Four questions, in order. Any one failing exits non-zero.
#
#   1. INTEGRITY  — do the bytes match the checksum Clojars publishes beside them?
#   2. PROVENANCE — is every source file in the jar identical to the git tag?
#      This is the one that matters and it is NOT the same as integrity: a jar
#      can be internally consistent and still have been built from a dirty tree.
#      Compared by CONTENT, deliberately — a zip is not byte-reproducible
#      (timestamps, ordering), so rebuilding and diffing bytes would fail for
#      reasons that are not defects.
#   3. SHAPE      — did anything leak in that should not ship? build.clj excludes
#      the *_check.cljc conformance programs; they are runnable scripts, not
#      library code.
#   4. PURITY     — does the published POM declare anything but org.clojure/clojure?
#      koine's whole reason to exist is that a consumer inherits no Java-carrying
#      dependency. Nothing checked that against the ARTIFACT until now; the
#      promise lived in deps.edn and a README sentence.
set -uo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-$(sed -n 's/^(def version "\(.*\)")$/\1/p' build.clj)}"
TAG="v${VERSION}"
BASE="https://repo.clojars.org/net/clojars/muthuishere/koine/${VERSION}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0
fail () { echo "  FAIL: $*"; FAILED=$((FAILED + 1)); }

echo "== verifying net.clojars.muthuishere/koine ${VERSION} against ${TAG} =="

# ---------------------------------------------------------------- 1. integrity
if ! curl -fsSL -o "$WORK/koine.jar" "${BASE}/koine-${VERSION}.jar"; then
  echo "  FAIL: Clojars does not serve ${VERSION} (not published yet?)"; exit 1
fi
PUB_SHA1="$(curl -fsSL "${BASE}/koine-${VERSION}.jar.sha1" 2>/dev/null | tr -d '[:space:]')"
GOT_SHA1="$(shasum -a 1 "$WORK/koine.jar" | cut -d' ' -f1)"
SHA256="$(shasum -a 256 "$WORK/koine.jar" | cut -d' ' -f1)"
if [ -z "$PUB_SHA1" ]; then
  fail "no .sha1 published beside the jar"
elif [ "$PUB_SHA1" != "$GOT_SHA1" ]; then
  fail "sha1 mismatch — published ${PUB_SHA1}, got ${GOT_SHA1}"
else
  echo "  ok   integrity: sha1 matches the checksum Clojars publishes"
fi
echo "       sha256 ${SHA256}   <- pin this"

# --------------------------------------------------------------- 2. provenance
if ! git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  fail "no local tag ${TAG} to compare against (git fetch --tags)"
else
  unzip -qq -o "$WORK/koine.jar" -d "$WORK/x"
  DIFFS=0; N=0
  for f in "$WORK"/x/koine/*.cljc; do
    [ -f "$f" ] || continue
    N=$((N + 1)); rel="src/koine/$(basename "$f")"
    if ! git show "${TAG}:${rel}" 2>/dev/null | diff -q - "$f" >/dev/null 2>&1; then
      fail "jar's $(basename "$f") differs from ${TAG}:${rel}"; DIFFS=$((DIFFS + 1))
    fi
  done
  # the reverse direction too: a namespace present at the tag but MISSING from
  # the jar is just as wrong, and comparing only what shipped cannot see it
  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    [ -f "$WORK/x/koine/$(basename "$rel")" ] || { fail "${rel} is in ${TAG} but NOT in the jar"; DIFFS=$((DIFFS + 1)); }
  done <<< "$(git ls-tree --name-only "${TAG}" src/koine/ | grep '\.cljc$')"
  [ "$DIFFS" -eq 0 ] && echo "  ok   provenance: ${N} source files identical to ${TAG}, none missing"
fi

# -------------------------------------------------------------------- 3. shape
LEAKED="$(unzip -Z1 "$WORK/koine.jar" | grep -E '_check\.cljc$|^conformance\.cljc$' || true)"
if [ -n "$LEAKED" ]; then
  fail "conformance programs leaked into the jar: $(echo "$LEAKED" | tr '\n' ' ')"
else
  echo "  ok   shape: no *_check.cljc / conformance.cljc in the artifact"
fi

# ------------------------------------------------------------------- 4. purity
POM="$WORK/x/META-INF/maven/net.clojars.muthuishere/koine/pom.xml"
if [ ! -f "$POM" ]; then
  fail "no pom.xml inside the jar"
else
  # every <artifactId> that appears inside a <dependency> block
  DEPS="$(tr -d '\n' < "$POM" \
          | grep -o '<dependency>.*</dependencies>' \
          | grep -o '<artifactId>[^<]*</artifactId>' \
          | sed 's/<[^>]*>//g' | sort -u | tr '\n' ' ')"
  DEPS="$(echo "$DEPS" | xargs || true)"
  if [ "$DEPS" = "clojure" ] || [ -z "$DEPS" ]; then
    echo "  ok   purity: declares nothing beyond org.clojure/clojure"
  else
    fail "published POM declares more than clojure: ${DEPS}"
  fi
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "PUBLISHED ARTIFACT VERIFIED — ${VERSION} == ${TAG}"
  exit 0
else
  echo "PUBLISHED ARTIFACT SUSPECT — ${FAILED} check(s) failed"
  exit 1
fi
