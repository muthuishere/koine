# examples

Two real projects — Clojure (JVM) and cljgo — that consume **koine from
Clojars** and run **the same source and the same tests** on both runtimes.

```bash
./run-both.sh        # every installed host, then diffs the app's output
```

```
== Clojure (JVM) - tests   Ran 9 tests containing 34 assertions. 0 failures
== cljgo - tests           Ran 9 tests containing 34 assertions. 0 failures
== diff                    jvm == cljgo
```

## Layout

```
clojure-app/         the Clojure (JVM) project - and the ONE copy of the source
  deps.edn           koine {:mvn/version "0.4.1"} from Clojars
  src/demo/app.cljc        the app
  test/demo/app_test.cljc  the suite
cljgo-app/           the cljgo project
  build.cljgo        (dep b "net.clojars.muthuishere/koine" {:mvn/version "0.4.1"})
  src/demo/app.cljc        -> ../clojure-app/src/demo/app.cljc
  test/demo/app_test.cljc  -> ../clojure-app/test/demo/app_test.cljc
```

Every project keeps the conventional split — source in `src/`, suite in `test/`.
That was briefly not true, in two different ways, and both are now gone:

- `cljgo test` used to walk `src/`/`test/` for `.clj` and `.cljg` only and
  **silently skip `.cljc`** — reporting "Ran 0 tests … 0 failures" and exiting 0,
  which reads as a green suite rather than one that never ran. The cljgo project
  had to symlink `app.cljg -> app.cljc` to be seen at all. Fixed upstream in
  cljgo `sourceFiles` (with a test) and **released in v0.8.2** — so these are
  plain `.cljc` symlinks in the ordinary places again.
**The cljgo project requires cljgo ≥ v0.8.2.** On v0.8.1 its suite reports
"Ran 0 tests … 0 failures" and exits 0 — green, having run nothing.

## Run them individually

```bash
cd clojure-app && clojure -M:test          # the suite
                  clojure -M -m demo.app   # the app, prints JSON

cd cljgo-app   && cljgo build              # resolve from Clojars, native binary
                  cljgo test               # the suite (build once first, to resolve)
                  ./demo

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
as `-128` everywhere (Go's byte is unsigned — koine normalises), that a *second*
JSON-RPC turn still reaches a live child, that `find-files` is sorted, and that
the emitted JSON has sorted keys.

## Degrading honestly, without a `catch`

Both hosts do everything today, but the app still ASKS rather than assuming —
because a `try`/`catch` probe is not portable, and because this is the shape
that lets a future host be added without touching the app:

```clojure
(if (host/supports? :fs/bytes)
  (fs/write-bytes path bs)     ; the real thing
  (fs/write-file  path (str payload)))   ; the honest fallback, recorded as binary? false
```

The suite does the same, so a documented gap reads as a skipped case and a
genuine regression still reads as red.

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
