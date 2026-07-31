# Porting notes — read this before adding a namespace to koine

## The two hosts

**Clojure (JVM)** and **cljgo**, both supported outright. A gap on either blocks
a release; there are no lower tiers and no "informational" hosts, because a host
koine does not promise would still cost every branch, every docstring and every
conformance row.

Run everything with `./run-conformance.sh`. A change is not verified until it is
green on both.

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

1. **Comparing functions is not portable.** Apply one; never compare one.
2. **`^:dynamic` is not honoured everywhere.** Thread the parameter through — it
   is also less code (`koine.json`'s `key-fn` is the example).
3. **Go's `os.Getenv` returns `""` where the JVM returns `nil`**, and `""` is
   truthy, so `(or (get-env x) default)` silently never falls back. `koine.env`
   normalises empty to nil.
4. **A shared name is not a portable function** — `file-seq` takes a
   `java.io.File` on the JVM and a string path on cljgo.
5. **Map print order differs per host**, so any assertion over `pr-str` of a map
   is a false failure waiting to happen. It is also why the JSON encoder sorts
   keys.
6. **cljgo's AOT discovery pass substitutes `nil` for every host result**, so a
   nil-intolerant pure function applied to a host value fails at *build* time,
   not run time. This is the usual source of "works in `cljgo run`, fails in
   `cljgo build`".
7. **`lang.Char` does not coerce to Go `byte`** — pass the integer.
8. **Do not put a protocol in the API.** A host can ship `defprotocol` without
   `reify`/`deftype`/`defrecord`, leaving it declarable and never implementable.
   Return a map of closures — that is why `koine.process`'s child handle is one.
9. **Go's `byte` is unsigned**, so a byte is 128/255 there where the JVM gives
   -128/-1. koine normalises to the JVM contract at the boundary; `bytes_check`
   asserts it.
10. **Setting a host struct's fields may be rejected**, so `:dir`/`:env` ride an
    `sh -c` wrapper rather than assignment.
11. **A var that RESOLVES may still be unbound** ("cannot call nil"), so
    `(resolve 'x)` is not a capability probe.
12. **A capability probe must not be a `try`/`catch`** — the catch symbol is not
    the same on both hosts, so the probe becomes host-specific code. Ask
    `koine.host/supports?` instead.

## Verified core parity

These resolve and behave identically on both hosts, so use them freely and
do **not** wrap them: `future` `future?` `deref` `promise` `deliver` `atom`
`swap!` `slurp` `spit` `pmap` `read-string` `pr-str` `re-seq`
`subs` `format` `with-out-str` `bytes` `byte-array` `char` `string?`
`random-uuid` `rand-int` `long` `max` `quot`, `try`/`finally` (no catch — see
below), multi-arity `defn`, `:refer-clojure :exclude`, `sort-by` with vector
keys, `re-find`, `(char 0)`, `merge` with nil, `every?`/`some?`/`contains?`, and
`clojure.string`'s `includes?` `ends-with?` `starts-with?` `index-of`
`last-index-of` `split` `replace` `blank?`.

A top-level reader conditional with **no branch for the current host reads as
nothing** on both — so `#?(:cljgo (require '[cljg.process]))` is a safe way to
pay a host-specific require cost only where it applies.

`random-uuid` in particular means **id generation needs no seam**.

## Known gaps (throw a named error; do not fake these)

- **Pattern-based date formatting.** Go layouts (`2006-01-02`) and java.time
  patterns (`yyyy-MM-dd`) are different languages, and koine will not fake a
  translation. `koine.time/iso-str` / `parse-iso` cover the wire format, which
  is what protocols actually carry.
- **Anything needing a third-party dependency.** `deps.edn` carries
  `org.clojure/clojure` and nothing else; one Java-carrying dep would make koine
  JVM-only and destroy its reason to exist.

## On cljgo, assert on OUTPUT — never on the exit code

Two independent ways a cljgo program reports success while doing nothing:

- **`cljgo run <file>` does not call `-main`.** It evaluates the top-level forms
  and exits 0. A file whose work lives in `-main` prints nothing and looks like a
  program that ran fine and had nothing to say. (Verified 2026-07-31: a file with
  a top-level `println` and a `-main` printed only the top-level line, exit 0.)
  An interpreted entry point needs the call at the top level; `cljgo build`
  binaries DO invoke `-main`, so the two modes disagree.
- **`cljgo test` skipped `.cljc` entirely** until the fix landed, printing
  "Ran 0 tests containing 0 assertions. 0 failures" and exiting 0 — a green
  light for a suite that never ran. Fixed and **released in cljgo v0.8.2**; on
  v0.8.1 and earlier a `.cljc` suite is invisible.

Both are the same failure shape: exit 0 means "nothing threw", not "the thing
happened". Every check in `src/*_check.cljc` therefore prints an `n/n pass` line
and the runner reads THAT, not `$?`. The toolnexus port adopted the same rule
after hitting the `-main` case (2026-07-31).
