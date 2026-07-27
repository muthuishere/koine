# What koine needs from cljgo

cljgo is a **tier-1** host: a gap here blocks a koine release. Everything below
was measured on 2026-07-27 against cljgo 0.1.0-dev (both the installed binary
and the in-repo one), and each item says exactly what unblocks.

Ranked. **Ask 1 alone closes items 2–5**, so it is worth doing first even though
it is the largest.

---

## 1. `require-go` should reach the Go standard library

**Today:** the seed registry is five packages — `strings`, `strconv`, `math`,
`fmt`, `net/url` (`pkg/corelib/host.go:33-80`). `os`, `os/exec`, `time` and
`net/http` are all absent.

```clojure
(require-go '[os])            ;=> error: no such namespace: os
(require-go '[net/http])      ;=> returns cleanly, interns NOTHING;
                              ;   the later http/Get fails "no such namespace: http"
```

**Surprising result worth your check:** this fails in **AOT mode too**.
`cljgo build` on a file with `(require-go '[net/http :as http])` gives
`compiler error: no such namespace: http`. Given ADR 0010/0033 describe AOT
resolving through go/packages type facts, I may simply have invoked it wrong
(no `build.cljgo`, bare file). If AOT *does* work with proper project setup,
say so — it changes the shape of this ask, though the interpreter gap would
remain.

**Why it matters:** Go already has everything koine is missing —
`os.Getenv`, `time.Since`, `time.Sleep`, `exec.Cmd.StdinPipe`, `resp.Body`. One
registry change closes items 2, 3, 4 and 5 below at once, and makes cljgo a
first-class Go host rather than a special case. It also future-proofs: koine's
Go branch could then be written against the Go stdlib and work on any Go-hosted
Clojure, present or future.

**Note:** `require-go` returning cleanly while interning nothing is a bug in its
own right — a clean return reads as a successful capability probe.

---

## 2. Environment variables — `cljg.os/getenv`

**Today: cljgo has no way to read an environment variable at all.** Verified:
`cljg.os` is cron/service only, `System/getenv` does not exist, `(getenv …)`
does not resolve, and `require-go '[os]` fails.

```clojure
(cljg.os/getenv "HOME")     ; -> string, or nil when unset
```

**Unblocks:** `koine.env`, which currently throws a named error on cljgo. That
in turn blocks toolnexus's `${ENV_VAR}` expansion in MCP headers — i.e. all
remote-MCP authentication.

**Note the semantics:** Go's `os.Getenv` returns `""` for unset where the JVM
returns `null`, and `""` is **truthy** in Clojure. Please return **nil**, not
`""`, or every `(or (getenv x) default)` in the ecosystem silently misbehaves.

---

## 3. Streaming subprocess — `cljg.io/spawn`

**Today:** `cljg.io/exec` (`core/cljg/io.cljg:155`) is run-to-completion — it
takes `:in` as a *string* and returns after the child exits, so it cannot hold a
conversation.

```clojure
(let [ch (cljg.io/spawn ["mcp-server" "--stdio"] {:dir d :env e})]
  (cljg.io/send-line! ch json-rpc-request)
  (cljg.io/read-line! ch)      ; blocks for one line; nil at EOF
  (cljg.io/alive? ch)
  (cljg.io/close! ch))         ; closes stdin, waits, returns exit code
```

Go side is `exec.Cmd` + `StdinPipe`/`StdoutPipe` + `bufio` — the same shape as
`pkg/bri/io_proc.go` already uses for `-proc-exec`.

**Unblocks:** MCP **stdio** transport — roughly half of toolnexus §2, and the
one thing ADR 0009 calls "the only hard blocker."

Ideally also: kill the whole **process group** on close, so a runaway child tree
dies with its parent.

---

## 4. Monotonic clock and sleep — make the existing privates public

Both already exist in Go and work; only visibility is missing.

- `-nano-time` — a real `time.Since(bootInstant)`
  (`pkg/corelib/macro_support_builtins.go:6`), `defPrivate` into `clojure.core`,
  unresolvable from user code.
- `-sleep-ms` — same.

```clojure
(cljg.os/mono-nanos)   ; monotonic, for durations
(cljg.os/sleep ms)     ; block, return nil
```

**Unblocks:** `koine.time/mono-ms` currently falls back to a high-water-clamped
wall clock on cljgo, so durations are wrong across an NTP step; and `sleep!`
routes through `core.async/timeout`, which works but is a heavy way to sleep.

*Quirk noticed:* private vars in the `cljg.os` **namespace** ARE reachable when
fully qualified (`cljg.os/-sleep-millis` resolves), while private
`clojure.core` ones are not. Inconsistent — worth a look independently.

---

## 5. Streaming HTTP response — `cljg.net.http/request-stream`

**Today:** the only Go shim ends in `io.ReadAll(resp.Body)` followed by
`defer resp.Body.Close()` (`pkg/bri/net_http.go`), so the body is fully
buffered and the reader is closed before Clojure ever sees it. The namespace
exposes no other entry point.

Anything that yields the body incrementally is enough — a reader handle, or a
callback per line:

```clojure
(cljg.net.http/request-stream opts (fn [line] …))
```

**Unblocks:** `koine.stream/sse-post`, which works on JVM, Glojure and let-go
and throws a named error on cljgo. Without it, cljgo cannot consume streaming
LLM responses (toolnexus §8 streaming).

---

## 6. Smaller items

- **`core.async` aliases are referred into the `user` ns only**
  (`pkg/corelib/chan_builtins.go:632`, applied by `InitUserNS`). So `<!!`,
  `timeout`, `chan`, `go` resolve in a script but fail at **compile time**
  inside any `(ns …)` file. Library code must fully qualify them. Surprising,
  and it makes a working script a misleading capability probe.
- **`(str e)` on an exception prints `#object[*lang.ExceptionInfo]`** — a test
  asserting on error text silently passes. `ex-message` works; consider making
  `str` useful.
- **Process exit code** — no `System/exit` equivalent found. Every CLI needs one.
- **`cljg.io` has no `mkdir-p`, `rename`, `stat` (mtime/size), `temp-dir`, or
  binary `read-bytes`/`write-bytes`.** `rename` in particular is what makes a
  crash-safe write possible (write temp, then rename); toolnexus's durable task
  store needs it.

---

## Not asked for

Deliberately not requesting: TLS configuration, DNS, compression, mmap, FFI,
SQL drivers. koine's survey across toolnexus, agentic-nexus and rag-cite-nexus
found no first-party need for them, and folding them in would make koine a
runtime rather than a seam.
