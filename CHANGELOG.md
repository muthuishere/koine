# Changelog

Every released version of `net.clojars.muthuishere/koine`, what changed, and
**which host versions it was actually run against**.

Two rules for this file, both earned:

- **A "tested against" row states what was measured for THAT release.** A host
  version that was green for an older koine is not evidence for a newer one. Where
  the record does not exist, it says so rather than guessing.
- **Fixes name the defect and who found it.** Six of koine's shipped bugs were
  found by a consumer or a peer rather than by its own gate — see
  [ADR 0001](docs/adr/0001-checks-must-assert-the-discriminator.md). Recording who
  caught what is how the gate's blind spots stay visible.

koine's version is **its own**. It tracks koine's API, not cljgo's. The minimum
supported cljgo is **v0.8.5**.

Verified, everywhere below, means: 13 conformance checks on both hosts, the JVM
`clojure.test` suite, and both example projects — interpreted and AOT — consuming
the published Clojars artifact and producing byte-identical output.

---

## 0.9.1 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo **v0.8.8** and **v0.8.9** —
both released builds, verified by their Go module checksum rather than by
`cljgo version`. (v0.8.9 measured 2026-08-02, after the release: 26/26
host-checks green, exit 0.)

### Fixed
- **HTTP timeouts never applied on cljgo** — koine passed `:timeout-ms` where
  cljgo's API takes `:timeout`. The unknown key was ignored silently, so every
  deadline on that host did nothing from 0.1.0 onward: a request budgeted at
  150 ms ran the full 1500 ms and returned a plausible 200. Found by the
  **toolnexus** port, which probed four key spellings against a deliberately slow
  server instead of filing "the timeout is broken". `http_check` now asserts the
  **clock**, not just the result map — a value-only assertion could not have seen
  this, because with the wrong key there is no error to classify.

### Docs
- README states what this release was tested against, and that koine's version is
  its own.
- The "avoid cljgo v0.8.3/v0.8.4" block is gone; the README names the supported
  floor instead of a list of broken versions.

## 0.9.0 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.6

### Added
- `koine.process/await-exit!` and `await-stderr` — the polling loop as **API,
  not advice**. Twice a docstring had told callers to loop around a racy
  snapshot; nothing verifies a docstring. Where the correct usage is a loop, koine
  ships the loop.

## 0.8.2 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.6

### Docs
- `stderr-lines` returning `[]` means **nothing has arrived yet**, not "nothing
  was written". The distinction decides whether a caller may stop waiting.

## 0.8.1 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.6

### Docs (breaking to callers' assumptions)
- `exit-code` returning `nil` means **has not been seen to exit**, not "still
  running". A caller treating `nil` as "alive" will loop forever on a child that
  already died.

## 0.8.0 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.5

### Added
- `koine.process/exit-code` and `koine.fs/real-path` — the last two upstream asks
  cljgo accepted, now consumed rather than shimmed.
- `koine.host` capabilities extended: `:process/exit-code`, `:fs/real-path`.

## 0.7.3 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Fixed (breaking: JSON byte output)
- **0.7.2 sorted JSON object keys by LENGTH before content** — `clojure.core/compare`
  on vectors is length-first, not lexicographic, so `{"config":2,"artifacts":1}`.
  koine now uses an explicit code-point comparator.

  This one is worth remembering: the bug was **identical on both hosts**, so the
  cross-host differential gate was structurally blind to it. It was caught by the
  *example* suite asserting a property of a real payload — and every new case
  0.7.2 had added used **equal-length keys**.

## 0.7.2 — 2026-08-01

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Fixed (breaking: JSON byte output)
- **JSON key order was not host-independent.** `sort` compares UTF-16 code units
  on the JVM and UTF-8 bytes on Go. They agree across the entire BMP — which is
  why every conformance case passed for months — and diverge above it, because a
  supplementary character is a surrogate pair whose lead unit is below U+FFFD
  while its UTF-8 bytes are above. One emoji in a key broke byte-identity. Keys
  now sort **by code point** via a pure-`clojure.core` scan.

## 0.7.1 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Fixed
- **`:timeout-ms` bounded the report, not the call, on cljgo** — measured
  314 ms of budget against a 5008 ms actual. Both hosts returned an identical,
  entirely plausible `{:timed-out? true :exit nil}`; only the clock told them
  apart.
- **`mkdirs!` over an existing file** threw on cljgo and silently no-opped on the
  JVM. `fs_check` now enters the states where a function must **refuse**, not only
  the ones where it must succeed.

## 0.7.0 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Fixed (breaking)
- **`alive?` meant two different things** — `false` on the JVM and `true` on
  cljgo for a child that had exited on its own. The checks had covered liveness
  mid-conversation and around `kill!`, never a child nobody had stopped.

### Added
- `run-async!` is public — a daemon thread on the JVM, a `future` on cljgo.
- `src/shadow_check.cljc`: asserts that no koine name accidentally shadows
  `clojure.core` on **either** host, with an allowlist for declared shadows
  (`koine.process/close!`).

## 0.6.0 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Added
- An off switch for subprocesses (`kill!`, `:timeout-ms`) and filesystem
  mutation (`delete!`, `delete-tree!`, `temp-dir!`).

### Fixed
- **A 60-second JVM hang.** `sh`'s pipe readers ran on `future`, whose pool
  threads are non-daemon with a 60 s keepalive — so the JVM would not exit for a
  minute after the work was done. Caught because a passing check took 61 s.

## 0.5.0 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo v0.8.2

### Removed (breaking)
- **Glojure and let-go**, and the tier system with them. Two hosts, both
  supported outright: a gap on either blocks a release. There are no lower tiers —
  a runtime koine does not promise would still cost every branch, every docstring
  and every conformance row.
- Test suites moved back to `test/`: cljgo v0.8.2 shipped the `.cljc` walk fix
  (its test walk had skipped `.cljc` entirely and reported "Ran 0 tests").

## 0.4.2 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

### Fixed
- **A child's stderr must be drained or it deadlocks.** Added `stderr-lines`.

### Added
- Transport failures are **data**, not exceptions: `{:status nil :error :timeout
  | :dns | :connect-failed | :transport}`. No portable `catch` can tell those
  apart — the JVM has real exception classes but naming one is Java interop, and
  cljgo wraps all three in `*fmt.wrapError`. koine classifies once, at the seam.
- SSE streaming survives torn frames.

## 0.4.1 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

### Fixed
- **`koine.host` would not AOT-compile on cljgo** — a comment was truncated at
  90 *bytes*, splitting a multi-byte rune and producing `emit: illegal UTF-8`
  pointing at generated source the author never wrote. **Fixed upstream in cljgo**
  (with a test), not worked around here.

## 0.4.0 — 2026-07-31

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

### Added
- `koine.host` — which host, what it can do, and `supports?`, so a caller
  degrades **without** a host-specific `catch`. (A `try`/`catch` cannot be a
  capability probe: the catch symbol itself is not the same on every host.)
- `examples/` — real consumer projects on the published Clojars artifact, one
  shared source.

## 0.3.0 — 2026-07-30

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

### Changed (breaking)
- One Clojars coordinate for both hosts.
- `koine.route/proxy` → `forward`.

## 0.2.0 — 2026-07-30

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

### Added
- Byte I/O, ISO-8601 and base64 seams, consuming what cljgo's ADR 0110 landed
  upstream at koine's request.
- Go's `byte` is **unsigned** — a byte read on cljgo is 128/255 where the JVM
  gives -128/-1. koine normalises to the JVM contract at the boundary and
  `bytes_check` asserts it.

## 0.1.0 — 2026-07-30

**Tested against:** Clojure (JVM) 1.12.5 · cljgo — *version not recorded*

First release, as `net.clojars.muthuishere/koine`.

- `koine.http`, `koine.server`, `koine.stream`, `koine.time`, `koine.route`,
  `koine.process`, `koine.fs`, `koine.env`, `koine.json`.
- JSON is **pure `clojure.core`** — no host library, no dependency. Keys sorted,
  floats keep their fraction (`1.0` never becomes `1`), non-ASCII emitted
  literally. Wrapping two host JSON libraries does not survive contact with
  reality: Go's `encoding/json` and JVM `clojure.data.json` disagree on four of
  six basic payloads.
- Four cljgo blockers closed upstream first (cljgo ADR 0104), then consumed.

---

## Release process

**The order is fixed: CHANGELOG → tag → GitHub release → Clojars.** Clojars is
last because it is the only irreversible step — a version there can never be
re-deployed or withdrawn, so nothing is published until the log and the release
that explain it already exist.

1. **Verify.** `clojure -M:test`, `./examples/run-both.sh`, and
   `env -u CLJGO_SRC PATH="$HOME/go/bin:$PATH" ./run-conformance.sh` — which
   exits non-zero on any failure and prints a provenance header naming the exact
   host versions it measured.
2. **Copy the `Tested against:` line from that header.** Only a
   `(released build)` counts: the header asks the Go binary for its `mod`
   checksum rather than trusting `cljgo version`, because a PATH `cljgo` that
   rebuilds from a local tree reports a release-shaped number while measuring
   something else entirely. That mistake produced two retracted claims.
3. **Add the section above**, bump `build.clj`'s `version`, commit, tag `vX.Y.Z`,
   push.
4. **`gh release create vX.Y.Z --notes-file <that section>`** — the GitHub
   release carries the same text as this file, so the tag, the log and the notes
   cannot drift.
5. **`clojure -T:build deploy`** to Clojars, last (credentials from the
   environment only).

The full version of this, with the commands, is in
[CLAUDE.md](CLAUDE.md#cutting-a-release).
