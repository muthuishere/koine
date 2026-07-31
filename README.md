# koine

**Write `.cljc` once, run it on every Clojure.**

One API over four Clojure runtimes, from a single source file:

| tier | runtime | status |
|---|---|---|
| **supported** | Clojure (JVM) · cljgo | every capability, or it does not ship |
| **nice to have** | Glojure | implemented where straightforward |
| **best effort** | let-go | works today; never gates a release |

Supported means supported: a gap on JVM or cljgo blocks the release. The other
two are kept green because they are cheap signal, not because they are promised.

*Koine* was the common tongue: the dialect that let people who spoke different Greeks
understand each other. That is this library's whole job.

You write plain Clojure. koine supplies the handful of things `clojure.core` can't —
HTTP, subprocesses, the filesystem, environment variables — plus JSON, and it is the
**only** place in your codebase that contains a reader conditional. The Java and the Go
live inside koine and never surface.

Adding a fifth runtime is a change *inside koine*: one branch in one file, which every
library built on it inherits at once.

**Scope, deliberately narrow:** koine covers only what touches the host. Anything that
can be plain Clojure stays plain Clojure — which is why JSON ended up pure
`clojure.core` rather than in the seam. It is the floor, not an app framework.

```clojure
(ns my.app
  (:require [koine.http :as http]
            [koine.json :as json]))

;; identical source, identical bytes, on Clojure 1.12 and on cljgo
(-> (http/post-json "https://api.example.com/v1/chat"
                    {"authorization" (str "Bearer " (koine.env/get-env "API_KEY"))}
                    (json/write-str {:model "claude-opus-5" :temperature 1.0}))
    :body
    json/read-str)
```

## Why

Clojure on the JVM reaches the host through Java interop. Go-hosted Clojure
(cljgo) cannot do Java at all — it reaches the host through Go interop. So any
library that touches the outside world is locked to one host.

The usual answer is "just use a pure-Clojure library." We measured that: **eleven
popular Clojure libraries were scanned and zero were pure** — `data.json`,
`edamame`, `medley`, `tools.cli`, `rewrite-clj`, `babashka/http-client` all carry
Java interop. The pure subset of the ecosystem is not thin, it's empty.

koine takes the other route: put *all* the host code in one small library, behind one
portable API, and let everything above it be plain Clojure.

## What's in it

| namespace | what | how |
|---|---|---|
| `koine.json` | `write-str` / `read-str` | **pure `clojure.core`** — no host code, no deps |
| `koine.env` | `get-env` / `expand` | `System/getenv` (jvm, let-go) · `os.Getenv` (glojure) · `cljg.system/getenv` (cljgo) |
| `koine.http` | `request` / `post-json` | `java.net.http` · `net:http` · `http` ns · `cljg.net.http` |
| `koine.stream` | `sse-post` / `parse-sse-line` | `BodyHandlers/ofInputStream` · chunked `Body.Read` · `:as :stream` (let-go, cljgo) |
| `koine.process` | `sh` / `spawn` | `ProcessBuilder` · `os:exec` · `os` ns · `cljg.io` + `cljg.process` |
| `koine.fs` | `exists?` `directory?` `list-tree` `find-files` `read-bytes` `write-bytes` | `java.io.File` · `java.nio` · `cljg.io` · `io`/`os` ns |
| `koine.time` | `now-ms` `mono-ms` `sleep!` `iso-str` `parse-iso` | `System/nanoTime` · `java.time.Instant` · `cljg.date` · `cljg.system` |
| `koine.codec` | `encode` / `decode` / `decode-bytes` (base64) | `java.util.Base64` · `cljg.security` |

Reader features, confirmed from each implementation's source: `:clj` · `:cljgo`
(cljgo ADR 0036) · `:glj` (`pkg/reader/reader.go:1403`) · `:lg`
(`pkg/compiler/reader.go:1122`; let-go can also opt into `:clj`/`:bb` via
`set-read-clj!` / `LG_READ_CLJ`, off by default).

## The JSON encoder is ours on purpose

Delegating encoding to each host's JSON library looks obvious and is wrong. We
measured Go's `encoding/json` against JVM `clojure.data.json` on six basic
payloads — **four disagreed**:

| payload | Go | JVM `data.json` |
|---|---|---|
| `{b 1, a 2, c 3}` | `{"a":2,"b":1,"c":3}` (sorted) | `{"b":1,"a":2,"c":3}` (insertion) |
| `1.0` | `1` — fraction dropped | `1.0` |
| `"a<b>c&d"` | `"a<b>c&d"` | `"a<b>c&d"` |
| `"café ☃"` | literal UTF-8 | `"café ☃"` |

Two of those are semantic. A float collapsing to `1` changes the JSON type, which
matters to any schema declaring `"type": "number"`. And key ordering decides
whether two hosts produce byte-identical prompt prefixes — which is what provider
prompt caching depends on.

So `koine` owns encoding (~80 lines, pure Clojure, no host library) and makes
three choices once, for every host:

1. **object keys are sorted** — Clojure map iteration order is unspecified above
   8 entries and differs between host implementations;
2. **floats keep their fraction** — `1.0` never becomes `1`;
3. **non-ASCII is emitted literally**; only the seven JSON escapes and `<0x20`
   are escaped. HTML characters are not — that's a Go default, not a JSON rule.

Decoding is **also** ours, for a different reason. Delegating it looked free —
parsing has no formatting choices to disagree about — but across four hosts it
would mean four parsers to keep in agreement, and two are not even reachable:
cljgo's decoder is a private builtin, and Glojure does not ship `encoding/json`
in its default package set. One core-only parser is smaller *and* more portable.

## Status

**Early.** Verified on Clojure 1.12.5, cljgo 0.1.0-dev, Glojure and let-go.

The JSON conformance suite passes **9/9 on all four hosts** — including every
payload where the hosts' own JSON libraries diverged.

### Portability bugs this shook out

Writing one file for four hosts surfaces things that look fine on the JVM:

- **`(= key-fn keyword)` throws on Glojure** — "comparing uncomparable type
  `lang.ArityFn`". Functions are not comparable there. Apply the fn; never
  compare it.
- **`^:dynamic` is not honoured on Glojure** — "cannot dynamically bind
  non-dynamic var". Thread the parameter instead; it is also less code.
- **Go's `os.Getenv` returns `""` where the JVM returns `null`** — and `""` is
  *truthy* in Clojure, so `(or (getenv x) default)` silently never falls back on
  Go-hosted dialects. `koine.env` normalises empty to nil.
- **`file-seq` takes different argument types** — a `java.io.File` on the JVM, a
  string path on cljgo. It resolves on both, so it reads as portable and isn't.
- **Map print order differs across hosts** — so any test comparing `pr-str` of a
  map is a false failure waiting to happen. (This is also why the encoder sorts.)

### Known gaps

**Two rounds of cljgo gaps are now closed, both upstream rather than worked
around here.** Round one (cljgo ADR 0109): environment access, streaming
subprocess, streaming HTTP body, monotonic clock. Round two (cljgo ADR 0110):
byte-level I/O, ISO-8601 dates, base64, and two `clojure.core` parity bugs —
`str` of a UUID emitting `#uuid "…"`, and `clojure.string/replace` rejecting a
function replacement. Every one is implemented, conformance-tested on both
supported hosts, and verified in an AOT binary.

That is the pattern to expect: koine files the need upstream and waits, rather
than shipping a branch that throws on a tier-1 host. What remains:

- **No pattern-based date formatting**, deliberately. `koine.time/iso-str` and
  `parse-iso` cover the wire format; a `format` taking a pattern would have to
  reconcile Go layouts (`2006-01-02`) with java.time patterns (`yyyy-MM-dd`),
  which are different languages. koine will not pretend one is the other.
- **Do not shadow a `clojure.core` name.** cljgo's dependency resolver gates
  each namespace on a static Java-interop scan, and a bare `proxy` / `err` /
  `deftype`-shaped symbol can read as the JVM special form even when it is your
  own `defn` — which rejects the whole file, not just the fn. koine renamed two
  of these (`route/proxy` -> `forward`, `json/err` -> `parse-err`).
- **Java exception classes do not exist on cljgo** — `(Exception. "x")` and any
  `java.*` class name fail. Throw `ex-info`, catch `Throwable`. (The numeric
  tower, by contrast, is NOT a divergence: `*` throws on overflow on *both*
  hosts — "long overflow" on the JVM, "integer overflow" on cljgo — and `*'`
  promotes to BigInt on both. Measured 2026-07-30.)
- **gloat and Joker are untested.** Every seam function ends in a `:default`
  branch that throws a named, actionable error, so adding a dialect is one
  branch in one file.
- **Tier 2/3 hosts lack `process/sh`.** Glojure and let-go have no subprocess
  route, so `process_check`, `fs_check` and `mcp_check` do not run there. They
  are best-effort tiers and never gate a release (see `PORTING.md`).

## Install

**One artifact, one coordinate, both hosts.** cljgo resolves Maven deps from
Clojars as of its ADR 0095, so the git-coordinate workaround is gone — cljgo
pulls the same published jar the JVM does, and prunes `org.clojure/clojure` from
it (cljgo *is* the Clojure implementation; its `clojure.core` is embedded).

```clojure
;; deps.edn — JVM
net.clojars.muthuishere/koine {:mvn/version "0.3.0"}
```

```clojure
;; build.cljgo — cljgo
(defn build [b]
  (dep b "net.clojars.muthuishere/koine" {:mvn/version "0.3.0"})
  (install b (exe b {:name "myapp" :main "src/myapp/core.cljg"})))
```

**The API is unstable at `0.3.0`.** `koine.process`, `koine.route` and
`koine.server` are the most likely to move; `koine.json`, `koine.env`,
`koine.time`, `koine.fs` and `koine.codec` are settled.

`0.3.0` renamed `koine.route/proxy` to **`koine.route/forward`** — the old name
shadowed `clojure.core/proxy`, which made the whole namespace unloadable on
cljgo (its Java-interop gate reads the bare symbol as the JVM class-definition
special form). If you were on `0.2.0`, that is the one call to change.

## Examples

Two real projects — one Clojure (JVM), one cljgo — consuming koine **from
Clojars** and running the **same source and the same tests** on both hosts:

```bash
./examples/run-both.sh
```

```
== Clojure (JVM) — tests      Ran 9 tests containing 31 assertions. 0 failures
== cljgo — tests              Ran 9 tests containing 31 assertions. 0 failures
== diff                       identical (modulo workdir, timestamp, duration)
```

`examples/clojure-app/src/demo/app.cljc` has no reader conditional, no `java.`
anything and no Go interop — it does HTTP, subprocesses, bytes, base64, env,
time and JSON through koine alone. See `examples/README.md`.

## Test

```bash
clojure -M:test          # JVM unit suite (100 tests, 338 assertions)
./run-conformance.sh     # every src/*_check.cljc on every installed host
```

Both supported hosts pass every check, interpreted **and** as a `cljgo build`
AOT binary (2026-07-30):

```
                      jvm    cljgo
conformance (json)    9/9    9/9
bytes_check          17/17  17/17   ← every byte 0-255, signed, through base64
env_check            12/12  12/12
fs_check             19/19  19/19
http_check            2/2    2/2
mcp_check             5/5    5/5    ← a real MCP stdio handshake through spawn
process_check        16/16  16/16
route_check          43/43  43/43
server_check         10/10  10/10
stream_check         29/29  29/29   ← arrival times, not just line content
time_check           28/28  28/28   ← includes exact ISO-8601 strings
```

`mcp_check` is the one that matters most: `initialize` →
`notifications/initialized` → `tools/list` → `tools/call` against
`@modelcontextprotocol/server-everything`, which is the workload `spawn` exists
for. It needs `npx` on PATH and skips cleanly without it.

## License

MIT
