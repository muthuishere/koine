# cljhost

**One `.cljc` library that makes Clojure code run unchanged on the JVM and on every Go-hosted Clojure.**

Verified on four hosts: **Clojure 1.12.5**, **cljgo**, **Glojure**, **let-go**.

Write portable Clojure. Call `cljhost` for the four things `clojure.core` doesn't
give you — HTTP, subprocesses, the filesystem, environment variables — plus JSON.
`cljhost` is the *only* place in your codebase that contains a reader conditional.

```clojure
(ns my.app
  (:require [cljhost.http :as http]
            [cljhost.json :as json]))

;; identical source, identical bytes, on Clojure 1.12 and on cljgo
(-> (http/post-json "https://api.example.com/v1/chat"
                    {"authorization" (str "Bearer " (cljhost.env/get-env "API_KEY"))}
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

`cljhost` takes the other route: put *all* the host code in one small library,
behind one portable API, and let everything above it be plain Clojure.

## What's in it

| namespace | what | how |
|---|---|---|
| `cljhost.json` | `write-str` / `read-str` | **pure `clojure.core`** — no host code, no deps |
| `cljhost.env` | `get-env` / `expand` | `System/getenv` (jvm, let-go) · `os.Getenv` (glojure) · gap (cljgo) |
| `cljhost.http` | `request` / `post-json` | `java.net.http` · `net:http` · `http` ns · `cljg.net.http` |
| `cljhost.process` | `sh` / `spawn` | `ProcessBuilder` · `os:exec` · `os` ns · `cljg.io` |
| `cljhost.fs` | `exists?` `directory?` `list-tree` `find-files` | `java.io.File` · `cljg.io` · `io`/`os` ns |

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

So `cljhost` owns encoding (~80 lines, pure Clojure, no host library) and makes
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
  Go-hosted dialects. `cljhost.env` normalises empty to nil.
- **`file-seq` takes different argument types** — a `java.io.File` on the JVM, a
  string path on cljgo. It resolves on both, so it reads as portable and isn't.
- **Map print order differs across hosts** — so any test comparing `pr-str` of a
  map is a false failure waiting to happen. (This is also why the encoder sorts.)

### Known gaps

- **cljgo cannot read environment variables.** `cljg.os` is cron/service only,
  there is no `System/getenv` shim, and `require-go` reaches only the seed
  registry — `strings`/`strconv`/`math`/`fmt` (`pkg/eval/host.go:15`) — so
  `(require-go '[os])` fails in **both** interpreted and AOT mode. Verified
  against cljgo 0.1.0-dev, both the installed and the in-repo binary.
- **`process/spawn` is unimplemented on cljgo.** A long-lived child with piped
  stdin/stdout (what a line-delimited JSON-RPC transport needs) has no cljgo
  primitive: `cljg.io/exec` is run-to-completion, and `require-go '[os/exec]`
  binds nothing in interpreted mode. It throws a named error rather than
  pretending. Needs a streaming primitive in cljgo's `cljg.io`.
- **gloat and Joker are untested.** Every seam function ends in a `:default`
  branch that throws a named, actionable error, so adding a dialect is one
  branch in one file.
- **Only JSON and env are conformance-tested across all four hosts so far.**
  `http`, `process` and `fs` have branches for each host but are verified on the
  JVM only.

## Install

```clojure
;; deps.edn — JVM
io.github.muthuishere/cljhost {:mvn/version "0.1.0"}

;; deps.edn — cljgo (Clojars consumption isn't supported by cljgo yet)
io.github.muthuishere/cljhost {:git/url "https://github.com/muthuishere/cljhost" :git/sha "…"}
```

## Test

```bash
clojure -M:test          # JVM unit suite (38 assertions)
./run-conformance.sh     # the same file on every installed host
```

```
== JSON encode/decode conformance ==
jvm        9/9 pass
cljgo      9/9 pass
let-go     9/9 pass
glojure    9/9 pass
```

## License

MIT
