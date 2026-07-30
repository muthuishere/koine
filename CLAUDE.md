# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this project is

**koine** — one small library that lets a single `.cljc` source file reach the
host (HTTP, subprocesses, filesystem, environment) on **four different Clojure
runtimes**, so that everything built on top of it can be plain, dialect-blind
Clojure.

*Koine* was the common tongue — the dialect that let people who spoke different
Greeks understand each other. That is the whole job.

| tier | runtime | reader feature | promise |
|---|---|---|---|
| **supported** | Clojure (JVM) | `:clj` | every capability, or it does not ship |
| **supported** | cljgo | `:cljgo` | every capability, or it does not ship |
| **nice to have** | Glojure | `:glj` | implemented where straightforward |
| **best effort** | let-go | `:lg` | kept green; never gates a release |

A gap on JVM or cljgo blocks a release. The other two are cheap signal, not a
promise.

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
- **Not a stable API yet.** See `INPROGRESS.md`. Nothing is published; the
  `koine.process` shape in particular is expected to move.

## Repo layout

| Path | What |
|---|---|
| `src/koine/*.cljc` | The library. One namespace per capability. |
| `src/conformance.cljc`, `src/*_check.cljc` | Host-parameterised conformance checks — run on **every** installed runtime. This is the real test suite. |
| `test/koine/*_test.cljc` | `clojure.test` suites. **JVM only.** |
| `run-conformance.sh` | Runs every check on every installed host, skipping the ones you don't have. |
| `docs/cljgo-requests.md` | What koine needs from cljgo, ranked, with evidence. |
| `INPROGRESS.md` | Current state + the road to a first Clojars release. |

## The prime directive

**One source, identical behaviour, four hosts — proven, not assumed.**

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
4. **Branch order is `#?(:clj … :cljgo … :glj … :lg … :default …)`**, extended
   in place. Adding a runtime is a branch, not a fork.
5. **Byte-identical output is the contract.** If a capability can produce
   differing bytes across hosts, normalise it and add a conformance check that
   asserts the agreement.

## Commands

```bash
clojure -M:test        # JVM clojure.test suite (41 tests / 181 assertions)
./run-conformance.sh   # every check on every installed host — the real gate
```

Installing the other hosts:

```bash
go install github.com/muthuishere/cljgo/cmd/cljgo@latest
go install github.com/glojurelang/glojure/cmd/glj@latest
go install github.com/nooga/let-go@latest
```

Note `clojure -M:test` only proves the JVM. **A change is not verified until
`run-conformance.sh` is green on at least JVM + cljgo.**

## Portability traps — learned the hard way

Each of these looked fine on the JVM and broke elsewhere. Treat them as rules.

1. **`(= key-fn keyword)` throws on Glojure** — "comparing uncomparable type
   `lang.ArityFn`". Apply a function; never compare one.
2. **`^:dynamic` is not honoured on Glojure** — "cannot dynamically bind
   non-dynamic var". Thread the parameter through; it is also less code.
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

## JSON is ours on purpose

Both encode and decode are pure `clojure.core`, no host library, no dependency.
Three choices are made once for every host and must not drift:

1. **Object keys are sorted** — map iteration order is unspecified above 8
   entries and differs per host. Consumers rely on this for byte-identical
   prompt prefixes (provider prompt caching).
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
| cljgo `docs/adr/0104-go-stdlib-interop.md` | The upstream change that unblocked koine's cljgo branches |
