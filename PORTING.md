# Porting notes — read this before adding a namespace to koine

Everything here was **measured** on 2026-07-27 against Clojure 1.12.5, cljgo
0.1.0-dev, Glojure and let-go (both built from source). Do not trust a README
over this file; if you find a difference, measure it and update this file.

## Support tiers — read this before deciding how hard to try

Effort is not spread evenly. A new seam function is DONE when tier 1 passes.

| tier | host | obligation |
|---|---|---|
| **1 — must** | Clojure (JVM), cljgo | Every capability MUST work. A gap here blocks the release. |
| **2 — nice to have** | Glojure | Implement when it is straightforward. If it fights back, throw the named error and move on — do not burn a day. |
| **3 — best effort** | let-go | Only if it falls out for free. Never a reason to delay or reshape anything. |

Tier 2 and 3 branches that already exist are kept and keep running in
`run-conformance.sh` — they are free signal, and a second Go runtime disagreeing
is the cheapest way to catch a Go-vs-JVM assumption before it reaches cljgo.
But they never gate a release and are not promised in the README.

Corollary: a tier-2/3 host failing a check is **information, not a bug to fix**.
Record it under "Known gaps" and carry on.

## The four hosts

| host | feature key | run a file | notes |
|---|---|---|---|
| Clojure (JVM) | `:clj` | `clojure -Sdeps '{:paths ["."]}' -M f.cljc` | |
| cljgo | `:cljgo` | `cljgo run f.cljc` | resolves namespaces relative to cwd |
| let-go | `:lg` | `lg f.cljc` | also answers `:clj`/`:bb` **only** if `LG_READ_CLJ`/`LG_READ_BB` set |
| Glojure | `:glj` | `GLJ_CLASSPATH=. glj f.cljc` | classpath via env var |

Run everything with `./run-conformance.sh`.

## Host interop, per dialect

- **JVM** — ordinary Java interop.
- **Glojure** — Go's stdlib directly, `/` munged to `:`. `(os.Getenv "HOME")`,
  `(os:exec.Command "echo" "hi")`, `(strings.ToUpper s)`. ~26 packages included
  by default (`bytes context errors flag fmt io io/fs math net/http os os/exec
  os/signal path/filepath reflect regexp sort strconv strings sync time
  unicode` …). Anything else needs a regenerated package map + a custom binary.
- **let-go** — its own namespaces `os` `io` `http` `json`, **plus Java-shaped
  shims**: `System/currentTimeMillis` and `System/getenv` both work. `(sleep n)`
  is a core fn. No `Thread/sleep`.
- **cljgo** — `cljg.io` / `cljg.net.http` / `cljg.os` namespaces (must be
  `:require`d). `require-go` reaches **only** the seed registry
  `strings`/`strconv`/`math`/`fmt` (`pkg/eval/host.go:15`) in *both* interpreted
  and AOT mode — `(require-go '[os])` fails. Treat Go interop as unavailable.

## Rules

1. **Reader conditionals live only in koine.** Consumers must never need one.
2. **Every seam fn ends in a `:default` branch** that throws a *named,
   actionable* error — never returns nil, never fails obscurely:
   `"koine.x/f: no implementation for this host; add a branch in koine/x.cljc"`.
3. **Anything expressible in plain `clojure.core` is NOT koine's job.** JSON is
   pure core for exactly this reason. koine is the floor, not a utility belt.
4. **Byte-identical output is the contract**, not "wraps a library". Where hosts
   disagree, koine picks one answer and normalises to it.
5. **A shared name is not a portable function.** If argument or return types
   differ across hosts, it goes behind the seam even when the symbol resolves
   everywhere (see `file-seq` below).

## Traps — every one of these cost real debugging time

- **`(= f g)` on functions THROWS on Glojure** — "comparing uncomparable type
  `lang.ArityFn`". Apply functions; never compare them.
- **`^:dynamic` is not honoured on Glojure** — "cannot dynamically bind
  non-dynamic var". Thread the parameter through instead; it is also less code.
- **Go's `os.Getenv` returns `""` where the JVM returns `null`**, and `""` is
  **truthy** in Clojure — so `(or (getenv x) default)` silently never falls
  back. Normalise empty to nil (`koine.env/blank->nil`).
- **`file-seq` takes a `java.io.File` on the JVM and a string path on cljgo.**
  Resolves on both; portable on neither.
- **Map print order differs per host.** Never assert on `pr-str` of a map — it
  is a false failure waiting to happen. (Also why the JSON encoder sorts keys.)
- **`future-cancel` hangs on Glojure.** Do not build cancellation on it there.
- **Unresolvable symbols fail at COMPILE time on let-go and Glojure**, so a
  `try`/`catch` around a probe does not save you and one bad form kills the
  whole file. Probe capabilities in *separate files*.
- **There is NO `catch` class symbol that compiles on all four hosts.** Full
  matrix: `Exception`/`Throwable`/`java.lang.Exception` work on jvm+cljgo+let-go
  (compile error on Glojure) · `go/any` on Glojure only · `Object` on let-go only
  · `Error` on jvm+cljgo only. **Consequence:** a namespace forbidden reader
  conditionals *cannot trap an error at all*, so it must be built on
  look-before-you-leap primitives (test then read) or on `try`/`finally`, which
  needs no catch clause and is portable.
- **Glojure catches with `go/any` — NOT `Exception`, NOT `Throwable`.** Both of
  those fail to *compile* there; Glojure's own stdlib uses `(catch go/any e …)`
  (`pkg/stdlib/clojure/core.glj`). The portable form is
  `#?(:glj (catch go/any e …) :default (catch Exception e …))`. `Exception` does
  work on cljgo and let-go. (An earlier revision of this file said "catch
  `Exception`" — that was wrong and cost an agent real time.)
- **`ex-message` does not exist on Glojure** — use `(str e)`. And on cljgo
  `(str e)` prints `#object[*lang.ExceptionInfo]`, so use `ex-message` there. A
  test asserting on error *text* must branch, or it silently passes.
- **cljgo refers the core.async aliases (`<!!` `timeout` `chan` `go` …) into
  `user` ONLY** (`chan_builtins.go:632` `asyncCoreAliases`, applied by
  `InitUserNS`). They resolve in a top-level script but fail at **compile time**
  inside any `(ns …)` file. Fully qualify them in library code:
  `clojure.core.async/<!!`. A probe script that works proves nothing about a
  namespace.
- **let-go's `System/nanoTime` is a WALL clock, not monotonic** —
  `pkg/rt/system.go:121` is `time.Now().UnixNano()`. The Java-shaped shims are
  named like Java without promising Java's guarantees; check the semantics, not
  the symbol.
- **cljgo's monotonic clock and sleep exist but are private.** `-nano-time`
  (`pkg/corelib/macro_support_builtins.go:6`, a real `time.Since(bootInstant)`)
  and `-sleep-ms` are `defPrivate` in `clojure.core` and unresolvable from user
  code. Quirk: private vars in the `cljg.os` *namespace* ARE reachable when
  fully qualified (`cljg.os/-sleep-millis`) while private `clojure.core` ones
  are not — depend on neither, both are outside the contract.
- **JVM `BodyHandlers/ofLines` is NOT incremental.** It looks like the obvious
  streaming route and is not: every line surfaces only after the whole body
  arrives (measured — 4 events all at ~605-767ms, spread 0). `sendAsync` is the
  same. Only `ofInputStream` + a `BufferedReader` genuinely streams. This would
  have shipped silently, since the events themselves are all correct.
- **let-go's HTTP client and server both panic on an empty-but-present
  `:headers {}`** — nil-pointer inside the host (`pkg/rt/http.go:134`); `{}` is
  neither `nil` nor walkable there. Omit the key entirely when there are no
  headers. koine absorbs this on both sides.
- **cljgo's `(require-go '[net/http])` SUCCEEDS and interns nothing** — the
  later `http/Get` then fails with `no such namespace: http`. A clean
  `require-go` return is NOT a capability probe.
- **Glojure has no `.-Field` form** — `(.-Header r)` dies with
  `panic: unimplemented op: 22`. Use `(.Header r)` for both fields and methods.
- **Glojure's `byte-array` produces `[]int8`, not `[]byte`** — anything feeding
  a Go `Read([]byte)` needs `(go/make (go/slice-of go/byte) n)`.
- **Glojure surfaces Go multi-returns as a vector** `[value error]` — `(nth r 0)`
  / `(first …)`. `[]byte`→string is `(.String (bytes.NewBuffer b))`.
- **`bufio` is NOT in Glojure's default package map** (`bytes`, `io`, `net/http`
  are). `bytes.Buffer.ReadBytes(10)` covers line framing; there is no
  `bufio.Scanner`.
- **Glojure coerces a Clojure fn to a Go func automatically**
  (`pkg/lang/apply.go:287`, `reflect.MakeFunc`), so a plain `(fn [w r] …)` IS an
  `http.HandlerFunc`. Go callbacks need no ceremony.
- **Server request-map keys differ on every host and none of them is
  `:method`/`:path`**: let-go `:request-method`/`:path`, cljgo/bri
  `:request-method`/`:uri`, Glojure `.Method`/`.URL.Path`, JVM
  `.getRequestMethod`/`.getRequestURI`. Header case differs too (Go
  canonicalizes `Content-Type`, let-go/bri lowercase). Normalise above the seam.
- **Split chunked reads on the 0x0A BYTE, never decode each chunk to text
  first.** 0x0A cannot appear inside a UTF-8 sequence, but a 4 KiB read lands
  mid-rune routinely. Verified with a 12000-byte non-ASCII line.
- **The JVM lingers ~60s after `future`/`pmap`** (non-daemon agent pool), so a
  timeout-guarded probe reports a false failure. Not a missing capability.

## Verified core parity

These resolve and behave identically on all four hosts, so use them freely and
do **not** wrap them: `future` `future?` `deref` `promise` `deliver` `atom`
`swap!` `slurp` `spit` `pmap` `read-string` `pr-str` `re-seq`
`subs` `format` `with-out-str` `bytes` `byte-array` `char` `string?`
`random-uuid` `rand-int` `long` `max` `quot`, `try`/`finally` (no catch — see
below), multi-arity `defn`, `:refer-clojure :exclude`, `sort-by` with vector
keys, `re-find`, `(char 0)`, `merge` with nil, `every?`/`some?`/`contains?`, and
`clojure.string`'s `includes?` `ends-with?` `starts-with?` `index-of`
`last-index-of` `split` `replace` `blank?`.

**NOT portable, despite appearances:** `agent` and `send`. `(agent 1)` panics on
Glojure ("invalid memory address or nil pointer dereference") and on let-go
returns an **Atom**, not an agent — a silent semantic swap. (An earlier revision
of this file listed both as verified parity. That was wrong.)

Go-method interop of the form `(.UnixMilli t)` / `(.Milliseconds d)` also works
uniformly on the Go-hosted dialects.

A top-level reader conditional with **no branch for the current host reads as
nothing** on all four — so `#?(:cljgo (require '[cljg.os]))` is a safe way to pay
a host-specific require cost only where it applies.

`random-uuid` in particular means **id generation needs no seam**.

## Known gaps (throw a named error; do not fake these)

- `koine.fs` has **no `:lg`/`:glj` branches** — the filesystem seam is 2-host
  while `slurp`/`spit` are 4-host, which is easy to miss. The primitives exist
  and are cheap: let-go `os/stat` (nil when missing, `{:dir? bool}` otherwise),
  Glojure `(os.Stat p)` → `[FileInfo err]` with `.IsDir`. let-go also has
  `os/free-port`, which would give it the `:port 0` support `serve` refuses.
- `slurp` throws on a missing file (and on a directory) on all four hosts — no
  host quietly returns nil, so "just slurp and see" is never an option.
- cljgo: no streaming subprocess (`cljg.io/exec` is run-to-completion).
- cljgo: no environment-variable access at all.
- cljgo: no `System/currentTimeMillis` (use the public `cljg.os/now`); no public
  monotonic clock or sleep, though both exist privately — worth filing upstream
  as `cljg.os/mono-nanos` + `cljg.os/sleep`, since the Go seams already exist and
  only visibility is missing.
- cljgo cannot stream an HTTP response: `cljg.net.http`'s only shim ends in
  `io.ReadAll` + `defer resp.Body.Close()` (`pkg/bri/net_http.go`), so the reader
  is closed before Clojure sees it, and the namespace exposes no other entry
  point. `koine.stream` throws a named error there.
- let-go cannot **stop** an HTTP server: `http/serve` (`pkg/rt/http.go:164`)
  calls `http.ListenAndServe` directly and returns NIL — no `*http.Server`
  exists anywhere in the runtime, so there is no shutdown handle and no
  bound-port readback.
- Glojure cannot bind **port 0**: its default package set has `net/http` but not
  plain `net`, so no `net.Listener` can be built and `*http.Server` never
  exposes its listener. An explicit port is required.
- let-go and cljgo have **no true monotonic clock** reachable from user code, so
  `koine.time/mono-ms` falls back to a high-water-clamped wall clock there.
