# Porting notes — read this before adding a namespace to koine

Everything here was **measured** on 2026-07-27 against Clojure 1.12.5, cljgo
0.1.0-dev, Glojure and let-go (both built from source). Do not trust a README
over this file; if you find a difference, measure it and update this file.

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
- **`Throwable` does not exist on Glojure.** Catch `Exception`, or don't catch.
- **The JVM lingers ~60s after `future`/`pmap`** (non-daemon agent pool), so a
  timeout-guarded probe reports a false failure. Not a missing capability.

## Verified core parity

These resolve and behave identically on all four hosts, so use them freely and
do **not** wrap them: `future` `future?` `deref` `promise` `deliver` `atom`
`swap!` `slurp` `spit` `pmap` `send` `agent` `read-string` `pr-str` `re-seq`
`subs` `format` `with-out-str` `bytes` `byte-array` `char` `string?`
`random-uuid` `rand-int`.

`random-uuid` in particular means **id generation needs no seam**.

## Known gaps (throw a named error; do not fake these)

- cljgo: no streaming subprocess (`cljg.io/exec` is run-to-completion).
- cljgo: no environment-variable access at all.
- cljgo: no `System/currentTimeMillis`; `cljg.os` has no `sleep`.
