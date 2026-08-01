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
- **Not a stable API yet.** Published as `net.clojars.muthuishere/koine` (0.7.3
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
| `run-conformance.sh` | Runs every check on every installed host, skipping the ones you don't have. |
| `docs/cljgo-requests.md` | What koine needs from cljgo, ranked, with evidence. |
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
go install github.com/muthuishere/cljgo/cmd/cljgo@v0.8.5   # >= v0.8.5
```

Note `clojure -M:test` only proves the JVM. **A change is not verified until
`run-conformance.sh` is green on at least JVM + cljgo.**

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
| `INPROGRESS.md` | Current state, publish readiness, open questions |
| `docs/cljgo-requests.md` | What koine needs from cljgo, with evidence |
| toolnexus `docs/adr/0009-clojure-port-cljc-dual-host.md` | Why koine exists; the measurements behind every decision here |
| cljgo `docs/adr/0109-go-stdlib-interop.md` | Round one upstream: env, streaming subprocess, streaming HTTP, monotonic clock |
| cljgo `docs/adr/0110-bytes-dates-and-core-parity.md` | Round two upstream: bytes, ISO dates, base64, core parity — plus the addenda for everything found since |
| `examples/README.md` | The four consumer projects, and how a caller degrades honestly |
