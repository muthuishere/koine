# ADR 0001 — a check must assert the DISCRIMINATOR, not the shape

- Status: accepted
- Date: 2026-08-01
- Supersedes: nothing. koine had no ADRs; this is the first.

## Context

koine's gate is 13 conformance checks run on both hosts, plus a JVM unit suite.
It has been green almost continuously. In the same period, **six defects shipped
to Clojars** and every one was found by a consumer or a peer, never by the gate.

| # | defect | what the check asserted | what it could not see |
|---|---|---|---|
| 1 | `alive?` answered `false` on the JVM and `true` on cljgo for a self-exited child | liveness mid-conversation, and around `kill!` | a child nobody had stopped |
| 2 | `mkdirs!` over an existing file threw on cljgo, silently no-opped on the JVM | that `mkdirs!` creates directories | the case where it must refuse |
| 3 | `:timeout-ms` bounded the report, not the call — 314 ms vs 5008 ms | `:timed-out? true`, `:exit nil` — identical on both | the clock |
| 4 | JSON keys sorted by UTF-16 units on the JVM, UTF-8 bytes on Go | six payloads, all inside the BMP | a code point above U+FFFF |
| 5 | JSON keys sorted by LENGTH before content (`{"config":2,"artifacts":1}`) | the new above-BMP cases — all EQUAL-LENGTH keys | keys of differing length |
| 6 | HTTP `:timeout-ms` passed to cljgo under a key it does not define, so every deadline was ignored | `:timeout-ms` as a safety bound on tests expected to fail otherwise | that a deadline ever fires |

Six defects, one shape. In every case the check exercised the states where the
two implementations **coincide**, and asserted the **shape of a result** rather
than the property that tells a correct implementation from a broken one.

Two of them are worth dwelling on because they defeat the obvious remedy:

- **#5 was identical on both hosts.** A cross-host differential gate is
  structurally blind to a bug both hosts share. It was caught by the *example*
  suite asserting a property of a real application payload.
- **#6 produced a plausible success.** With the wrong key there is no error to
  classify, so a case checking `:error` would have read as a missing feature
  rather than a broken one. Only the elapsed time distinguished them.

## Decision

**Every conformance case must assert something that differs between a correct
implementation and the most plausible broken one.** If a case would pass against
an implementation that does nothing, does the wrong thing consistently, or
returns a plausible default, it is not a check — it is a demonstration.

Four rules follow, each earned by a defect above:

1. **Test the unhappy state, not only the happy one.** Ask what the function
   must REFUSE, not only what it must do (#1, #2).
2. **Assert the clock when the feature is about time.** A deadline's contract is
   elapsed time; a result map is not evidence (#3, #6).
3. **Choose inputs that vary the dimension under test.** Above-BMP *and*
   differing lengths; a single-segment *and* a nested namespace (#4, #5).
4. **A fixture that cannot occur cannot discriminate.** Borrowed from cljgo's
   #189, where a test fixture paired a module version with VCS data — a
   combination that never occurs — and that fiction hid the bug, because the two
   real cases are told apart by exactly the field the fixture gave to both.

And one that is not about tests at all:

5. **Advice is not a fix.** Twice koine corrected a docstring that told callers
   to poll around a racy snapshot, while the library and its tests already did
   the right thing. Nothing verifies a docstring. Where the correct usage is a
   loop, ship the loop — `await-exit!` and `await-stderr` exist for this reason.

## Consequences

**Accepted cost.** These checks are slower and uglier. `http_check` now runs a
server that sleeps 1500 ms; `process_check` spawns a child that never answers.
Deliberate: the fast version of each of those passed while the feature was
broken.

**Not claimed.** This does not fix the class. It is five rules derived from six
instances, and the seventh will be a dimension not listed here. The honest claim
is that these six are now covered and their shape is written down.

**No differential-only gate.** Cross-host agreement remains necessary and is not
sufficient (#5). Where an external anchor exists — RFC 4648 base64 arithmetic,
code-point values derived from Unicode rather than from koine's output — the
expectation is derived from it, never snapshotted from what koine currently
emits. An expectation copied from your own output enshrines the bug as a
regression test defending it.

## Spike: does the ordering rule scale? (s01)

Rule 2 says assert the clock. Applying it to koine's own hot path found a real
cost, so the rule is recorded here with its measurement rather than as advice.

`spikes/s01_json_scaling.cljc`, encode a map of N keys, cost per key:

| n | cljgo before | cljgo after | JVM before | JVM after |
|---|---|---|---|---|
| 100 | 689 µs | **222 µs** | 13.9 µs | **6.3 µs** |
| 400 | 934 | 287 | 8.1 | 4.4 |
| 1600 | 1179 | 352 | 14.5 | 4.8 |
| 6400 | 1436 | **417** | 12.2 | **5.6** |

**Finding.** `sort-by` applies its key-fn once per COMPARISON, so the key scan
ran O(n log n) times instead of O(n). The per-key cost rose with `log n` — 689 →
1436 µs as the map grew 64×. The JVM hid it entirely (flat within noise) because
`nth` on a UTF-16 string is O(1) there, where cljgo's strings are rune-indexed.
**One host paid, and only at size.**

**Fix.** Decorate-sort-undecorate: scan each key once, sort the decorated pairs.
3.1× faster on cljgo, 2.2× on the JVM.

**What is NOT claimed.** Per-key cost still rises with `log n` after the fix
(222 → 417 µs over 64×). That is what a comparison sort costs and it is not
removable while keys are sorted — which they must be, since byte-identical
output is the contract. No cache and no lazy layer was added; the fix was to
stop doing redundant work, not to memoise it.

**Observation, not an action.** cljgo remains ~50–75× slower than the JVM here
in absolute terms (417 µs vs 5.6 µs per key). That is interpreter overhead, not
koine's algorithm, and it is cljgo's to weigh — recorded so the ratio is not
rediscovered as a koine regression later.
