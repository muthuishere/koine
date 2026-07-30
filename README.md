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
| `koine.fs` | `exists?` `directory?` `list-tree` `find-files` | `java.io.File` · `cljg.io` · `io`/`os` ns |

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

The four cljgo blockers this section used to list — no environment access, no
streaming subprocess, no streaming HTTP body, no monotonic clock — **all closed
on 2026-07-30** (cljgo ADR 0109 plus the `cljg.system` / `cljg.process` /
`cljg.date` / `cljg.net.http` APIs). Every one is now implemented, conformance-
tested on both supported hosts, and verified in an AOT binary. What remains:

- **No byte-level I/O.** `fs/read-file` / `write-file` are `slurp` / `spit`, so
  text only. Binary read/write is the one genuinely missing filesystem seam.
- **No date formatting or parsing.** `koine.time` covers epoch millis, monotonic
  elapsed and sleep — deliberately not a `java.time` port.
- **`clojure.string/replace` with a function replacement is not portable** —
  cljgo's throws `replace expects a String, got: #object[fn]`. `koine.env/expand`
  hand-rolls its scan because of this; a caller doing the same needs to know.
- **Java exception classes do not exist on cljgo** — `(Exception. "x")` and any
  `java.*` class name fail. Throw `ex-info`, catch `Throwable`. (The numeric
  tower, by contrast, is NOT a divergence: `*` throws on overflow on *both*
  hosts — "long overflow" on the JVM, "integer overflow" on cljgo — and `*'`
  promotes to BigInt on both. Measured 2026-07-30.)
- **`(str (random-uuid))` differs** — cljgo yields `#uuid "…"` (44 chars), the
  JVM a bare 36-char UUID. Anything putting an id on the wire must not rely on
  `str` of a UUID. Filed upstream (cljgo ADR 0110).
- **No byte-level I/O and no base64 on cljgo**, so koine cannot offer a binary
  `fs` seam or MCP blob content yet. Also filed as cljgo ADR 0110.
- **cljgo cannot consume Clojars** (its ADR 0095 is proposed, not shipped), so
  cljgo users take the same source tree by git coordinate. One source, two
  coordinates — see Install.
- **gloat and Joker are untested.** Every seam function ends in a `:default`
  branch that throws a named, actionable error, so adding a dialect is one
  branch in one file.
- **Tier 2/3 hosts lack `process/sh`.** Glojure and let-go have no subprocess
  route, so `process_check`, `fs_check` and `mcp_check` do not run there. They
  are best-effort tiers and never gate a release (see `PORTING.md`).

## Install

One source tree, two coordinates — cljgo cannot resolve Maven deps yet (its
`dep` accepts `{:git …}` / `{:path …}` only), so JVM users take the Clojars
artifact and cljgo users take the same code by git.

```clojure
;; deps.edn — JVM
net.clojars.muthuishere/koine {:mvn/version "0.1.0"}
```

```clojure
;; build.cljgo — cljgo
(defn build [b]
  (dep b "koine" {:git "https://github.com/muthuishere/koine" :ref "v0.1.0"})
  (install b (exe b {:name "myapp" :main "src/myapp/core.cljg"})))
```

**The API is unstable at `0.1.0`.** `koine.process`, `koine.route` and
`koine.server` are the most likely to move; `koine.json`, `koine.env` and
`koine.time` are settled.

## Test

```bash
clojure -M:test          # JVM unit suite (82 tests, 281 assertions)
./run-conformance.sh     # every src/*_check.cljc on every installed host
```

Both supported hosts pass every check, interpreted **and** as a `cljgo build`
AOT binary (2026-07-30):

```
                      jvm    cljgo
conformance (json)    9/9    9/9
env_check            12/12  12/12
fs_check             19/19  19/19
http_check            2/2    2/2
mcp_check             5/5    5/5    ← a real MCP stdio handshake through spawn
process_check        16/16  16/16
route_check          43/43  43/43
server_check         10/10  10/10
stream_check         29/29  29/29   ← arrival times, not just line content
time_check           14/14  14/14
```

`mcp_check` is the one that matters most: `initialize` →
`notifications/initialized` → `tools/list` → `tools/call` against
`@modelcontextprotocol/server-everything`, which is the workload `spawn` exists
for. It needs `npx` on PATH and skips cleanly without it.

## License

MIT
