# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## workwire

name: koine
owns: the koine library — one `.cljc` source reaching the host (HTTP, subprocess, fs, env)
  across two Clojure runtimes (JVM, cljgo); host-parity semantics; what each can and cannot do
will-not-speak-for: consumers of koine, their build setups, or anything outside this repo
depends-on: the two host runtimes it targets (Clojure JVM, cljgo)
groups: @all

## What this project is

**koine** — one small library that lets a single `.cljc` source file reach the
host (HTTP, subprocesses, filesystem, environment) on **Clojure (JVM) and
cljgo**, so that everything built on top of it can be plain, dialect-blind
Clojure.

*Koine* was the common tongue — the dialect that let people who spoke different
Greeks understand each other. That is the whole job.

| runtime | reader feature | promise |
|---|---|---|
| Clojure (JVM) | `:clj` | every capability, or it does not ship |
| cljgo | `:cljgo` | every capability, or it does not ship |

Both are supported outright — a gap on either blocks a release. There are no
lower tiers and no "informational" hosts: a runtime koine does not promise would
still cost every branch, every docstring and every conformance row.

The reason koine exists at all: **the pure-Clojure ecosystem is empty, not
thin.** Eleven popular libraries were scanned — `data.json`, `edamame`,
`medley`, `tools.cli`, `rewrite-clj`, `babashka/http-client` — and *every one*
carries Java interop, so every one is JVM-only. Rather than wait for a pure
ecosystem, koine puts **all** the host code in one small library behind one
portable API.

## What this project is NOT

Read this before adding anything.

- **Not an application framework.** koine covers only what touches the host.
  Anything that can be plain Clojure stays plain Clojure — which is exactly why
  JSON ended up as pure `clojure.core` rather than in the seam. It is the
  **floor**, not a platform.
- **Not a place for third-party dependencies.** `deps.edn` has `org.clojure/clojure`
  and nothing else, deliberately. A single Java-carrying dependency would make
  koine JVM-only and destroy its reason to exist. This constraint is absolute.
- **Not a wrapper around each host's libraries.** Wrapping two host JSON
  libraries and calling it portable does not survive contact with reality: Go's
  `encoding/json` and JVM `clojure.data.json` disagree on four of six basic
  payloads. koine **normalises to one agreed output** and asserts byte-identity
  across hosts. "It compiles on both" is not the bar; "it emits the same bytes"
  is.
- **Not ClojureScript / `:cljr` / `:bb` / jank.** Out of scope. ClojureScript
  cannot spawn a subprocess, which rules out roughly half of what consumers need.
- **Not a stable API yet.** Published as `net.clojars.muthuishere/koine` (0.10.0
  at the time of writing) with the API explicitly marked unstable; `koine.route`
  and `koine.server` are the most likely to move. See `INPROGRESS.md`.

## Repo layout

| Path | What |
|---|---|
| `src/koine/*.cljc` | The library. One namespace per capability. |
| `src/koine/host.cljc` | Which host, what it can do, and `supports?` — how a caller degrades WITHOUT a host-specific `catch`. |
| `examples/` | Two consumer projects (JVM, cljgo) on the published Clojars artifact, one shared source, `./examples/run-both.sh`. |
| `src/conformance.cljc`, `src/*_check.cljc` | Host-parameterised conformance checks — run on **every** installed runtime. This is the real test suite. |
| `test/koine/*_test.cljc` | `clojure.test` suites. **JVM only.** |
| `scripts/verify-published.sh` | Checks the jar CLOJARS SERVES against the git tag — integrity, provenance, shape, dependency purity. |
| `run-conformance.sh` | Runs every check on every installed host. Prints the host versions it MEASURED (asking the binary, not its version string) and exits non-zero on any failure — so it gates a release rather than being read. |
| `docs/cljgo-requests.md` | What koine needs from cljgo, ranked, with evidence. |
| `docs/adr/` | Decisions with their evidence. ADR 0001 is the check-discipline one. |
| `spikes/` | Measurements. Numbers, growth, and what is NOT claimed. |
| `CHANGELOG.md` | Per-release log + the release process. A release is not done until its section names the host versions it was MEASURED on. |
| `INPROGRESS.md` | Current state + the road to a first Clojars release. |

## The prime directive

**One source, identical behaviour, both hosts — proven, not assumed.**

1. **Reader conditionals live only in `src/koine/`.** That is the entire point:
   consumers write plain Clojure and never see a `#?`. A capability that leaks
   host detail into the consumer's code is a bug in koine.
2. **A name that resolves on both hosts is not thereby portable.** If argument
   or return *types* differ, it goes behind the seam. `file-seq` is the canonical
   trap: it resolves everywhere, but takes a `java.io.File` on the JVM and a
   string path on cljgo.
3. **Every branch ends in `:default`** that either delegates to a
   dialect-agnostic implementation or throws a **named, actionable** error —
   `"koine: no <capability> implementation for this host; add a branch in
   koine/<ns>.cljc"`. Never a silent `nil`, never an obscure resolution failure.
4. **Branch order is `#?(:clj … :cljgo … :default …)`**, extended in place.
   Adding a runtime is a branch, not a fork.
5. **Byte-identical output is the contract.** If a capability can produce
   differing bytes across hosts, normalise it and add a conformance check that
   asserts the agreement.

## Commands

```bash
clojure -M:test          # JVM clojure.test suite (109 tests / 372 assertions)
./run-conformance.sh     # every check on every installed host — the real gate
./examples/run-both.sh   # the published artifact, consumed on both hosts
```

Installing the other hosts:

```bash
go install github.com/muthuishere/cljgo/cmd/cljgo@v0.9.0   # >= v0.8.5
```

Note `clojure -M:test` only proves the JVM. **A change is not verified until
`run-conformance.sh` is green on at least JVM + cljgo.** That script exits
non-zero on any failure and prints WHAT IT MEASURED before it runs — see below.

## Cutting a release

**The order is: CHANGELOG → Clojars → examples → git tag → GitHub release.**

Clojars sits in the middle for one measured reason, not by preference:
**`cljgo` resolves Maven deps from remote repositories only — it does not read
`~/.m2`** (verified 2026-08-02: `cljgo build` in `examples/cljgo-app` against a
locally-installed 0.10.0 → "not found in any repository", tried repo1 and
repo.clojars.org). The cljgo example consumes the *published* artifact on
purpose, so it physically cannot run before publishing.

What that costs is bounded, and everything that CAN run before the irreversible
step does: the 13 conformance checks on both hosts, the 109 JVM unit tests, the
JVM example against a local `install`, **and an AOT compile of the changed
namespaces from source** (step 1) — which is the one that matters, because 0.4.1
was a failure that only appeared under AOT.

The **tag and the GitHub release stay last**, so nothing is discoverable until
the artifact behind it is green. If the examples fail after publish, ship a
patch release: a Clojars version can never be re-deployed or withdrawn.

1. **Verify everything that does not need Clojars, and capture what you
   verified against.**
   ```bash
   clojure -M:test                                          # JVM suite
   env -u CLJGO_SRC PATH="$HOME/go/bin:$PATH" ./run-conformance.sh
   clojure -T:build install                                 # 0.x.y into ~/.m2
   (cd src && cljgo build -o /tmp/aot <ns>_check.cljc && /tmp/aot)   # AOT, per changed ns
   ```
   `run-conformance.sh` prints a provenance header and **exits non-zero** if any
   host-check fails or fails to report. Take the host versions for the changelog
   **from that header**, never from memory and never from `cljgo version`.

   **Only a `(released build)` line is release evidence.** The header asks the
   binary, not the version string: a `go install …@vX.Y.Z` build carries a `mod`
   checksum and no `vcs` stanza; anything else prints `NOT a release` and must
   not be written into the changelog. This is not hypothetical — the `cljgo` on
   PATH here is a 422-byte shim that rebuilds from `../cljgo` on every call while
   reporting a release-shaped version number, and believing it produced two
   claims that had to be retracted. Install the tag and put `$HOME/go/bin` first.

   **Do the AOT compile even though `run-conformance.sh` was green.** It runs
   cljgo interpreted; 0.4.1 was a bug that appeared ONLY under AOT, and single-
   file `cljgo build` resolves koine from the source tree, so this needs no
   published artifact. Run the resulting binary, do not just build it.

2. **Update `CHANGELOG.md`** — a new section with the version, the date, the
   **`Tested against:` line copied from that header**, and what changed. A fix
   names the defect and **who found it**; seven of koine's shipped defects were
   found by a consumer or a peer rather than by its own gate, and recording the
   finder is how the gate's blind spots stay visible (ADR 0001).

3. **Bump `build.clj`'s `version`** and the two example coordinates
   (`examples/clojure-app/deps.edn`, `examples/cljgo-app/build.cljgo`), commit.

4. **Deploy to Clojars** — `clojure -T:build deploy`. Credentials come from the
   environment (`CLOJARS_USERNAME`, `CLOJARS_PASSWORD` = a deploy token); the
   value never enters a file, a command echo or this repo. The coordinate is
   `net.clojars.muthuishere/koine`: Clojars pre-verifies `net.clojars.<user>`,
   while `io.github.<user>` needs a one-time GitHub verification this group never
   had (403 on deploy, 2026-07-30).

   **This is the irreversible step.** A version can never be re-deployed or
   withdrawn; if what follows fails, the fix is a patch release.

5. **Now run the examples against the published artifact**, which is the only
   point at which the cljgo one can resolve:
   ```bash
   rm -f examples/cljgo-app/build.lock.edn        # or it pins the OLD version
   env -u CLJGO_SRC PATH="$HOME/go/bin:$PATH" ./examples/run-both.sh
   ```
   Commit the regenerated `build.lock.edn`. **Never hand-edit that file** — it
   is generated and carries checksums; editing the version in place leaves the
   old checksums, the build fails, and `run-both.sh` silences the build and then
   runs a STALE binary, reporting a divergence that is not real. That cost a
   whole debugging session once.

6. **Verify what Clojars actually serves** — not what you think you uploaded:
   ```bash
   ./scripts/verify-published.sh X.Y.Z
   ```
   Integrity (bytes vs published checksum), **provenance** (every source file
   identical to the git tag — a jar can be internally consistent and still built
   from a dirty tree), shape (no `*_check.cljc` leaked), and **purity** (the POM
   declares nothing but `org.clojure/clojure`). Exits non-zero on any of them.
   Record the printed `sha256` in the changelog so consumers can pin.

   Asked for by the toolnexus port, which caught a lock/registry mismatch with
   the same check: a jar in `~/.m2` that was a LOCAL install, so a lock built
   against it pinned a hash the registry does not serve. koine had nothing
   equivalent and the purity promise was, until now, only a sentence.

7. **Tag and cut the GitHub release, last** — so nothing is discoverable until
   the artifact behind it is green:
   ```bash
   git tag vX.Y.Z && git push origin main --tags
   awk '/^## X\.Y\.Z/{f=1;next} /^## /{f=0} f' CHANGELOG.md > /tmp/rel.md
   gh release create vX.Y.Z --title "koine X.Y.Z" --notes-file /tmp/rel.md
   ```
   The release notes are the changelog section verbatim, so the tag, the log and
   the notes cannot drift.

## When the bug is the host's, fix the host

**A defect that genuinely belongs to cljgo gets FIXED IN CLJGO, not worked around
here.** cljgo is ours (`../cljgo`, `muthuishere/cljgo`), it is a supported host, and
koine is usually the first real consumer to hit anything — so a workaround in
koine hides a bug every other cljgo user will meet later, and leaves koine
carrying the scar tissue forever.

The order:

1. **Measure it.** Reduce to a minimal repro that does not mention koine. If it
   cannot be reproduced without koine, it is probably koine's bug.
2. **Fix it upstream** — patch + test in `../cljgo`, run its gate (`gofmt -l`,
   `go vet ./...`, `go test ./pkg/... ./cmd/...`). Small, self-contained fixes:
   just do them.
3. **File it instead ONLY when the fix is a design decision** — new CLI surface,
   a changed contract, anything with more than one defensible answer. Open a
   GitHub issue (`gh issue create`) with the repro, the code sites, and a
   recommendation. Record it in the live cljgo ADR too.
4. **Then release and consume the fix**, rather than keeping a local shim.
5. **Never quietly paper over it.** If koine must carry a temporary workaround
   until a fix ships, say so in the code, name the upstream issue, and say what
   deletes it.

Precedent, all 2026-07-30/31 — every one of these was a host bug found *by
consuming koine*, and none was reachable from cljgo's own suite (its sources are
`.cljg`; its docstrings never happened to land an em dash on byte 90):

| symptom | actually | outcome |
|---|---|---|
| `koine.env/expand` threw on cljgo | `clojure.string/replace` rejected a fn replacement | fixed upstream |
| no env / spawn / stream / clock | four missing capabilities | ADR 0109 upstream, then used |
| no bytes / dates / base64 | ADR 0110 asks | implemented upstream, then used |
| `cljgo test` reported "Ran 0 tests … 0 failures" | the walk skipped `.cljc` entirely | **fixed upstream** (+test) |
| `emit: 73:92: illegal UTF-8` | comment truncated at 90 *bytes*, splitting a rune | **fixed upstream** (+test) |
| `run resolve with -update` | no such command exists | **issue #166** — CLI design call |
| `load-file` resolves, then "cannot call nil" | unbound var | **issue #167** |

The two fixed ones are the shape to aim for: an hour upstream removed a silent
green-when-broken test run and a build failure that pointed at generated source
the author never wrote.

## Portability traps — learned the hard way

Each of these looked fine on the JVM and broke elsewhere. Treat them as rules.

1. **Comparing functions is not portable** — apply a function; never compare
   one.
2. **`^:dynamic` is not honoured everywhere.** Thread the parameter through; it
   is also less code.
3. **Go's `os.Getenv` returns `""` where the JVM returns `nil`** — and `""` is
   *truthy* in Clojure, so `(or (getenv x) default)` silently never falls back
   on Go-hosted dialects. Normalise empty to `nil`.
4. **`file-seq` takes different argument types** (see prime directive 2).
5. **Map print order differs per host** — any assertion over `pr-str` of a map
   is a false failure waiting to happen. It bit the conformance script itself,
   and it is also why the JSON encoder sorts keys.
6. **cljgo's AOT discovery pass substitutes `nil` for every host result** — so a
   nil-intolerant pure function applied to a host value fails at **build** time,
   not run time. Keep host results on a nil-tolerant path. *(New, 2026-07-30 —
   the most likely source of "works in `cljgo run`, fails in `cljgo build`".)*
7. **`lang.Char` does not coerce to Go `byte` on cljgo** — pass the integer.
8. **Do not put a protocol in the API.** A host can ship `defprotocol` without
   `reify` / `deftype` / `defrecord` / `extend-type`, leaving it declarable and
   never implementable. Return a **map of closures**; that is why
   `koine.process`'s child handle is a map, as `koine.server`'s already was.
9. **Go's `byte` is UNSIGNED** — a byte read on a Go-hosted dialect is 128/255
   where the JVM gives -128/-1. koine normalises to the JVM contract at the
   boundary; `bytes_check` asserts it.
10. **Setting a host struct's fields may be rejected**, so `:dir`/`:env` ride an
    `sh -c` wrapper rather than assignment.
11. **A var that RESOLVES may still be unbound** ("cannot call nil"), so
    `(resolve 'x)` is not a capability probe. It does not show up until called.
12. **A capability probe must not be a `try`/`catch`** — the catch symbol is not
    the same everywhere, so the probe itself becomes host-specific. Ask
    `koine.host/supports?` instead.

## JSON is ours on purpose

Both encode and decode are pure `clojure.core`, no host library, no dependency.
Three choices are made once for every host and must not drift:

1. **Object keys are sorted BY CODE POINT** — map iteration order is unspecified
   above 8 entries and differs per host. Consumers rely on this for
   byte-identical prompt prefixes (provider prompt caching).

   Sorting is not sufficient, and this cost a real bug: the hosts disagree on
   what *sorted* means. `sort` compares UTF-16 code units on the JVM and UTF-8
   bytes on Go. They agree across the entire BMP — which is why every
   conformance case passed for months — and diverge above it, because a
   supplementary character is a surrogate pair whose lead unit is BELOW U+FFFD
   while its UTF-8 bytes are ABOVE. One emoji in a key was enough to break
   byte-identity. koine now sorts by code point (== UTF-8 byte order) via a
   pure-`clojure.core` scan; `conformance` covers it. Fixed in 0.7.2.
2. **Floats keep their fraction** — `1.0` never becomes `1`. Go's
   `encoding/json` drops it, which silently changes the JSON *type* under a
   `"type":"number"` schema.
3. **Non-ASCII is emitted literally** — only the seven JSON escapes and `<0x20`
   are escaped. HTML escaping is a Go default, not a JSON rule.

If you touch the encoder: **no `StringBuilder`, no `Long/parseLong`, no
`Double/parseDouble`, no Java interop of any kind.** Build strings with
`apply str` / `clojure.string/join`. That was the first thing the original spike
got wrong.

## Conventions

- **Idiomatic Clojure, not a transliteration of Go or Java.** The host code
  hides inside the seam; the API above it reads like Clojure.
- **Secrets are use-only.** Never write a real key into source, a test, a check
  or a comment — read it from the environment, and use an obvious fake
  (`YOUR_KEY_HERE`) for placeholders. Header values are never logged.
- **Conventional commits**: `feat:`, `fix:`, `docs:`, `test:`, `chore:`. Do
  **not** add `Co-authored-by:`.
- **Cite the source, not the README**, for any claim about a host's behaviour —
  reader-feature and interop claims are verified against each implementation's
  source, and measurements are re-run rather than remembered.
- Touch only what the task needs; note unrelated issues rather than fixing them
  inline.

## Consumers

- **toolnexus** (Clojure port, ADR 0009) — the driver. It depends on
  `clojure.core` + koine and nothing else, so any dependency added here lands in
  its supply chain too.
- Intended for any dual-host Clojure library — the seam is not toolnexus-specific.

## Related docs

| Document | Purpose |
|---|---|
| `README.md` | What koine is, for users |
| `CHANGELOG.md` | Every release: host versions MEASURED, defects fixed, who found them. Also the release process. |
| `INPROGRESS.md` | Current state, publish readiness, open questions |
| `docs/cljgo-requests.md` | What koine needs from cljgo, with evidence |
| toolnexus `docs/adr/0009-clojure-port-cljc-dual-host.md` | Why koine exists; the measurements behind every decision here |
| cljgo `docs/adr/0109-go-stdlib-interop.md` | Round one upstream: env, streaming subprocess, streaming HTTP, monotonic clock |
| cljgo `docs/adr/0110-bytes-dates-and-core-parity.md` | Round two upstream: bytes, ISO dates, base64, core parity — plus the addenda for everything found since |
| `examples/README.md` | The four consumer projects, and how a caller degrades honestly |
