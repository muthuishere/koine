# cljhost

**One `.cljc` library that makes Clojure code run unchanged on the JVM and on Go-hosted Clojure.**

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

| namespace | what | JVM | cljgo |
|---|---|---|---|
| `cljhost.json` | `write-str` / `read-str` | own encoder + `data.json` decode | own encoder + `cljg.net.http` decode |
| `cljhost.http` | `request` / `post-json` | `java.net.http` | `cljg.net.http` |
| `cljhost.process` | `sh` / `spawn` | `ProcessBuilder` | `cljg.io` (`spawn`: see gaps) |
| `cljhost.fs` | `exists?` `directory?` `list-tree` `find-files` | `java.io.File` | `cljg.io` |
| `cljhost.env` | `get-env` / `expand` | `System/getenv` | `cljg.os` |

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

Decoding *is* delegated, because parsing has no formatting choices to disagree
about.

## Status

**Early.** Verified on Clojure 1.12.5 and cljgo 0.1.0-dev.

The JSON encoder contract suite passes byte-identically on both hosts — including
all four payloads where the hosts' own libraries diverged.

### Known gaps

- **`process/spawn` is unimplemented on cljgo.** A long-lived child with piped
  stdin/stdout (what a line-delimited JSON-RPC transport needs) has no cljgo
  primitive: `cljg.io/exec` is run-to-completion, and `require-go '[os/exec]`
  binds nothing in interpreted mode. It throws a named error rather than
  pretending. Needs a streaming primitive in cljgo's `cljg.io`.
- **Only `:clj` and `:cljgo` are verified.** Glojure, let-go, gloat and Joker are
  untested. Every seam function ends in a `:default` branch that throws a named,
  actionable error, so adding a dialect is one branch in one file — but
  "seamless across Go-hosted Clojures" is a design goal here, not a claim.

## Install

```clojure
;; deps.edn — JVM
io.github.muthuishere/cljhost {:mvn/version "0.1.0"}

;; deps.edn — cljgo (Clojars consumption isn't supported by cljgo yet)
io.github.muthuishere/cljhost {:git/url "https://github.com/muthuishere/cljhost" :git/sha "…"}
```

## Test

```bash
clojure -M:test          # JVM
```

## License

MIT
