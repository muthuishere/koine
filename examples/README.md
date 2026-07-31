# examples

Two real projects — one Clojure (JVM), one cljgo — that consume **koine from
Clojars** and run **the same source and the same tests** on both hosts.

```bash
./run-both.sh        # tests on both hosts, then diffs the app's output
```

```
== Clojure (JVM) — tests      Ran 9 tests containing 31 assertions. 0 failures
== cljgo — tests              Ran 9 tests containing 31 assertions. 0 failures
== diff                       identical (modulo workdir, timestamp, duration)
```

## Layout

```
clojure-app/         the Clojure (JVM) project — and the ONE copy of the source
  deps.edn           koine {:mvn/version "0.3.0"} from Clojars
  src/demo/app.cljc      the app
  test/demo/app_test.cljc  the suite
cljgo-app/           the cljgo project
  build.cljgo        koine {:mvn/version "0.3.0"} from Clojars — same artifact
  src/demo/app.cljg       -> symlink to ../clojure-app/src/demo/app.cljc
  test/demo/app_test.cljg -> symlink to ../clojure-app/test/demo/app_test.cljc
```

The symlinks are the honest version of "one source": there is no second copy to
drift, and the cljgo project reads the exact bytes the JVM project does. They
exist only because `cljgo test` walks `src/`/`test/` for `.clj` and `.cljg` and
**silently skips `.cljc`** — it reports "Ran 0 tests … 0 failures" and exits 0,
which reads as a green suite. (`require` handles `.cljc` fine; `load-file`
resolves but is unbound, so it is not an alternative.) Filed upstream; when the
walk learns `.cljc`, delete the two symlinks and nothing else changes.

## Run them individually

```bash
cd clojure-app
clojure -M:test              # the suite
clojure -M -m demo.app       # the app, prints JSON

cd cljgo-app
cljgo build                  # resolve from Clojars, compile a native binary
cljgo test                   # the suite (needs one `cljgo build` first, to resolve)
./demo                       # the binary
```

## What the example demonstrates

**`src/demo/app.cljc` contains no reader conditional, no `java.` anything, and
no Go interop.** That is the entire point — every host-shaped thing goes
through koine:

| in the app | koine | the host thing it hides |
|---|---|---|
| `(env/get-env "DEMO_TOKEN")` | `koine.env` | `System/getenv` vs `cljg.system/getenv`, and Go's `""`-for-unset |
| `(fs/write-bytes path bs)` | `koine.fs` | `java.nio.Files` vs `cljg.io` — and `slurp` would corrupt these bytes |
| `(codec/encode bs)` | `koine.codec` | `java.util.Base64` vs `cljg.security` |
| `(t/iso-str)` / `(t/mono-ms)` | `koine.time` | `java.time.Instant` vs `cljg.date`; nanoTime vs Go's monotonic clock |
| `(proc/spawn ["cat"])` | `koine.process` | `ProcessBuilder` vs `cljg.process` — a long-lived child, two round trips |
| `(json/write-str …)` | `koine.json` | neither host's JSON library: they disagree on key order and on `1.0` |

The suite asserts the things that actually differ between hosts if a seam is
wrong: that an unset env var is `nil` and not `""`, that byte `0x80` comes back
as `-128` on both, that a *second* JSON-RPC turn still reaches a live child,
that `find-files` is sorted, and that the emitted JSON has sorted keys.

`run-both.sh` then diffs the two hosts' output. It normalises only the three
things that legitimately differ — the workdir (set per host so they cannot
clobber each other), the timestamp, and the measured duration. Everything else
must match byte for byte, and does.

## The dependency resolve is itself a check

cljgo prints this while resolving:

```
cljgo deps: net.clojars.muthuishere/koine 0.3.0 — 10 namespace(s) with no Java interop
  pruned org.clojure/clojure 1.12.5 (cljgo IS the Clojure implementation)
```

That line is koine's claim being machine-verified at dependency time: cljgo
statically gates every namespace in the jar, and one that reached for Java would
be rejected — which is exactly how `koine.route/proxy` was caught shadowing
`clojure.core/proxy` in 0.2.0 (it is `forward` now).
