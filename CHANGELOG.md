# Changelog

Every released version of `net.clojars.muthuishere/koine`, what changed, and
**which host versions it was actually run against**.

Two rules for this file, both earned:

- **A "tested against" row states what was measured for THAT release.** A host
  version that was green for an older koine is not evidence for a newer one. Where
  the record does not exist, it says so rather than guessing.
- **Fixes name the defect and who found it.** Seven of koine's shipped bugs were
  found by a consumer or a peer rather than by its own gate — see
  [ADR 0001](docs/adr/0001-checks-must-assert-the-discriminator.md). Recording who
  caught what is how the gate's blind spots stay visible.

koine's version is **its own**. It tracks koine's API, not cljgo's. The minimum
supported cljgo is **v0.8.5**.

Verified, everywhere below, means: 13 conformance checks on both hosts, the JVM
`clojure.test` suite, and both example projects — interpreted and AOT — consuming
the published Clojars artifact and producing byte-identical output.

---

## 0.11.0 — 2026-08-02

**Tested against:** Clojure (JVM) 1.12.5 · cljgo **v0.9.0** (released build,
verified by Go module checksum)
**Published artifact:** `sha256 7879b732e4a5a092d670575cf9c2172fd76039ff2b09eebf8dae0ca988f448e3`,
verified against `v0.11.0` by `scripts/verify-published.sh`.

### Added

- **`koine.text`** — `code-points`, `compare-code-points`, `compare-strings`,
  `sort-strings`, `utf8-length`. Pure `clojure.core`; no host call, no interop,
  no reader conditional.

  **Why this belongs in koine, which is a floor and not a platform:** because
  `clojure.core` gives *different answers* on the two hosts. Measured on Clojure
  1.12.5 vs cljgo v0.9.0:

  | expression | JVM | cljgo |
  |---|---|---|
  | `(compare "😀" "\uE000")` | negative | **positive** |
  | `(count "😀")` | 2 — UTF-16 code units | **1** — one rune |
  | `(sort ["😀" "\uE000"])` | private-use first | **emoji first** |

  A JVM string is UTF-16 code units, so a supplementary character is a surrogate
  pair whose lead unit is *below* U+E000; a cljgo string is runes, ordered by
  UTF-8 bytes, which puts the same character *above*. They agree across the
  entire BMP and diverge above it — which is why this survives every test until
  one emoji reaches real data. That is prime directive 5, not scope creep.

  **This is not new code.** `code-points` and the length-safe comparator have
  been inside `koine.json` since 0.7.3, where they were bought with two shipped
  bugs: 0.7.2 sorted by UTF-16 unit, and its fix sorted by *length* first
  (`{"config":2,"artifacts":1}`) because `compare` on vectors is count-first.
  Making them public means a consumer reaches for the seam instead of
  rediscovering that the way koine and the toolnexus port both did — from a
  byte-exact output that silently differed. `koine.json` now delegates, so there
  is exactly one implementation rather than two that can drift.

  Proposed by the toolnexus Clojure port after fixing **nine** sites locally.

  Named `compare-strings`, not `compare`: shadowing `clojure.core/compare` would
  force every consumer into a `:refer-clojure :exclude`, and cljgo warns on the
  shadow *even with the exclusion declared* where the JVM is silent — a
  portability cost with no benefit.

- `koine.host` capability: `:text/code-points`.

### Notes

- `utf8-length` answers the question `count` cannot: how many **bytes** the
  string occupies on the wire. `count` gives UTF-16 units on the JVM and runes
  on cljgo — two different wrong answers for a `content-length` or a byte
  budget. Computed from the code points by RFC 3629 rules, so no host encoder is
  involved.

## 0.10.1 — 2026-08-02

**Tested against:** Clojure (JVM) 1.12.5 · cljgo **v0.9.0** (released build,
verified by Go module checksum)
**Published artifact:** `sha256 c600db144f63d73a236db3c9c3ebe8bc19b430804b11998f802723dfcd227925`,
verified against `v0.10.1` by `scripts/verify-published.sh`.

### Fixed

- **`delete!` refused a non-empty directory without saying so on the JVM.** Both
  hosts already agreed on *behaviour* — refuse, leave the tree intact — but not
  on explaining it:

  | host | message before |
  |---|---|
  | JVM | `/var/folders/…/sub` — the bare path, from `DirectoryNotEmptyException` |
  | cljgo | `cljg.io/delete!: directory is not empty` |

  A caller seeing only a path cannot tell a non-empty directory from a
  permission problem from a vanished mount. That fails prime directive 3 — an
  error must be **named and actionable**, not merely thrown. Both hosts now
  raise `koine.fs/delete!: directory is not empty: <path> — delete its contents
  first, or use koine.fs/delete-tree!`, with the host's own text preserved under
  `:host-message`.

  Surfaced by the toolnexus port, where a concurrent suite **killed a process**
  on this call. Their diagnosis was that the JVM "silently no-ops" where Go
  errors — true of raw `java.io.File/delete`, but koine's `:clj` branch uses
  `java.nio.file.Files`, which throws. So the divergence was never behavioural;
  it was only ever the message, which is why no behavioural check saw it.

  The new cases assert the **message**, and were proven to discriminate:
  reverting the fix fails exactly the three message assertions while "it throws"
  and "it leaves the tree intact" still pass — so a naive `(is (thrown? …))`
  would have shipped this.

### Docs

- **`delete-tree!` is documented as NOT safe against concurrent writers**, on
  either host — stated rather than fixed. The walk is taken once, so anything
  created under the path afterwards leaves its parent non-empty when `delete!`
  reaches it. Two suites sharing one fixture directory is the usual way to meet
  it, and it reports itself as a runtime flake rather than a concurrency bug —
  it will not reproduce when hunted in isolation, because isolation removes the
  collision. Give each process its own root; `temp-dir!` returns a fresh one per
  call.

## 0.10.0 — 2026-08-02

**Tested against:** Clojure (JVM) 1.12.5 · cljgo **v0.8.9** and **v0.9.0** —
released builds, verified by Go module checksum. (v0.9.0 measured 2026-08-02,
after the release: conformance green both hosts, examples green in all three
legs, plus 18 AOT runs — see the v0.9.0 note below.)

**On cljgo v0.9.0.** No koine change was needed. Two things were checked rather
than assumed:

- **ADR 0121 makes an unknown option a hard error** (`cljg.io/exec: unknown
  option :timeout`). koine hands option maps to three cljg APIs — `:dir`/`:env`
  to `cljg.process/spawn`, and `:method :url :timeout :headers :body :as` to
  `cljg.net.http/request` — and every key is one those APIs define, so nothing
  is rejected. Note this change would have turned koine's own 0.9.1 HTTP defect
  from a silent no-op into a loud error, which is the point of it.
- **#197, the AOT-only intermittent at ~1 in 4, does not reach koine.** 18 AOT
  runs across `http_check`, `stream_check` and `process_check` on the v0.9.0
  release binary, all green (20/20, 45/45, 48/48 each). At the stated rate the
  probability of 18 clean runs is 0.75^18 ≈ 0.6%, so this is evidence of
  absence on these paths — not a claim the bug is fixed, which is cljgo's to
  make.

### Fixed (breaking: response header keys)

- **Response header names were unreadable portably.** The hosts disagree
  natively and there was no third spelling that worked:

  | host | key returned | `(get headers "Mcp-Session-Id")` |
  |---|---|---|
  | JVM | `mcp-session-id` — `java.net.http` lowercases | **nil** |
  | cljgo | `Mcp-Session-Id` — Go's `http.Header` canonicalises | works |

  So `koine.http/request` handed back headers no portable code could read, and
  it failed **silently** — a missing header and a mis-cased one are both `nil`.
  Names are now **lowercased on every host**, which is the form the wire already
  uses (HTTP/2 requires it; RFC 7230 makes names case-insensitive, so nothing is
  lost). New: `koine.http/header` for a case-insensitive read and
  `koine.http/normalize-headers`.

  Found by the **toolnexus** MCP port, whose session id lives in exactly such a
  header. Seventh defect of the ADR 0001 shape, and a new dimension: no check
  had ever asserted header key *case*, and the fixtures all used lowercase
  header names — which one host matches by accident. The fixture now sends mixed
  case, because a lowercase one cannot discriminate.

  Note this was **not** catchable by comparing the two hosts' header maps for
  equality: they legitimately differ (`date`, and cljgo's server adds a
  `content-type` the JVM's does not). Only asking for a known key by a fixed
  spelling tells the two apart.

### Added

- **`sse-post` surfaces the response head before the first event.**
  `(sse-post url headers body on-event {:on-open f})` applies `f` once to
  `{:status n :headers {…}}` as soon as the head is available, while the stream
  is still open. `sse-post` also now returns `:headers` alongside `:status`.

  The 4-arity is unchanged and still supported.

  Why a callback rather than the returned map: MCP streamable-HTTP issues a
  session id in the response headers, and a server→client reverse request
  arriving as an SSE event must be answered by a **separate POST carrying that
  id, before the first stream closes**. Headers returned when the stream ends
  arrive strictly too late, and the buffered `koine.http/request` never streams —
  so a consumer previously had to choose between learning the session id and
  receiving events incrementally. Asked for by the toolnexus MCP port, which
  checked what koine already had before asking.

  The contract here is a **timing** one, so the check asserts the clock: that
  `on-open` fires before the first event and leads it by more than the server's
  gap. Verified by mutation — moving the callback to after the read loop failed
  exactly those two cases while all 43 value assertions still passed.

- `koine.host` capabilities: `:http/response-headers`, `:stream/response-head`.

### Tooling

- **`scripts/verify-published.sh`** — verifies the jar **Clojars actually serves**
  against the git tag: integrity (bytes vs published checksum), **provenance**
  (every source file identical to the tag — a jar can be internally consistent
  and still built from a dirty tree), shape (no conformance program leaked in),
  and **purity** (the published POM declares nothing but `org.clojure/clojure`,
  which was koine's central promise and was verified nowhere against the
  artifact). Exits non-zero on any.

  0.10.0 verified: 11 source files identical to `v0.10.0`, `sha256
  323126c576c14d24b39eacbf8a8ea40dab9c08019bdfa35b568d44fc0d7343db`.

  Asked for by the toolnexus port, which caught a lock/registry mismatch with
  the equivalent check on its side and said it had already broken their CI once
  before they built it. Proven to discriminate, not just to pass: against the
  wrong tag it flags exactly the three namespaces 0.10.0 changed
  (`host`/`http`/`stream`), and an unpublished version exits 1.

- `run-conformance.sh` prints the host versions it **measured** and **exits
  non-zero** on failure, where failure includes a check that did not report at
  all. It reads cljgo's provenance from the binary's Go module checksum, not
  from `cljgo version`, and says `NOT a release` for a build from a checkout or
  a wrapper script. See [CLAUDE.md](CLAUDE.md#cutting-a-release).

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

**The order is: CHANGELOG → Clojars → examples → tag → GitHub release.**

Clojars sits in the middle for a measured reason: **cljgo resolves Maven deps
from remote repositories only, not `~/.m2`**, so the cljgo example — which
consumes the published artifact on purpose — cannot run before publishing. The
tag and the GitHub release stay last, so nothing is discoverable until the
artifact behind it is green.

1. **Verify everything that does not need Clojars.** `clojure -M:test`,
   `env -u CLJGO_SRC PATH="$HOME/go/bin:$PATH" ./run-conformance.sh` (exits
   non-zero on any failure and prints the host versions it measured), and an
   **AOT compile of each changed namespace** from source — `cljgo build` in
   single-file mode resolves koine from the working tree, and 0.4.1 was a bug
   that appeared only under AOT.
2. **Copy the `Tested against:` line from that header.** Only a
   `(released build)` counts: the header asks the Go binary for its `mod`
   checksum rather than trusting `cljgo version`, because a PATH `cljgo` that
   rebuilds from a local tree reports a release-shaped number while measuring
   something else entirely. That mistake produced two retracted claims.
3. **Add the section above**, bump `build.clj` and both example coordinates,
   commit.
4. **`clojure -T:build deploy`** — the irreversible step. A Clojars version can
   never be re-deployed or withdrawn; if what follows fails, ship a patch.
5. **Run the examples against the published artifact.** Delete
   `examples/cljgo-app/build.lock.edn` first so it re-pins, and commit the
   regenerated one. Never hand-edit it.
6. **Tag, push, and `gh release create` with that same section** — the notes are
   the changelog verbatim, so the tag, the log and the notes cannot drift.

The full version of this, with the commands, is in
[CLAUDE.md](CLAUDE.md#cutting-a-release).
