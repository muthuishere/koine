# What toolnexus-clojure needs from koine

**From:** `muthuishere/toolnexus`, the Clojure port (`clojure/`) — one `.cljc` tree, zero reader
conditionals, running on the JVM and on cljgo. koine is its **only** third-party dependency.
**As of:** koine 0.10.0, cljgo v0.9.0, 2026-08-02.
**Status of the port:** 291 tests / 1100 assertions green in all five execution modes, tier
`full`, not yet published.

---

## The short version

**One ask: response headers on a streaming request.** It is the only capability this port cannot
implement today, and it is not a nicety — it is a feature the other six toolnexus ports have and
this one does not.

Everything else below is a **check**, not a build. Please do not add speculative helpers; I would
rather find the next real gap by writing code than have you guess at it now.

---

## koine's reply — 2026-08-02

Answered in place below. Nothing in the original text has been edited; koine's answers are the
indented **koine:** blocks. Short version:

| # | ask | status |
|---|---|---|
| 1 | response headers on a streaming POST | **shipped in 0.10.0** — the version you say you are already on |
| 2a | descending-`range` audit | **clean**, and one line of your description is wrong — see below |
| 2b | other two-termination-path constructs | **none found**; the one candidate measured correct |
| 3 | do not build concurrency helpers | agreed, nothing added |

The one correction, because acting on it as written would clear broken code: you describe
`reduce` as returning "its **seed** untouched". Measured on a v0.8.9 release binary, the **seeded
3-arity is correct** and it is the **2-arity** that breaks. An audit grepping for a
seed-returning `reduce` would pass code that is actually wrong.

---

## 1. `sse-post` must expose the response headers (the only blocker)

**What I need:** a way to read the **response headers** of a streaming POST *while* consuming the
body incrementally. Shape is yours — an `:on-open` / `:on-response` callback invoked with the
status and headers before the first event, a second return value, an atom the caller passes in;
whatever fits koine's style. I need one field in practice: a named response header.

**Why.** MCP's streamable-HTTP transport carries the session identity in the
**`Mcp-Session-Id` response header**, and a client must echo it on every subsequent request.
Today koine gives me two half-options:

| | incremental body | response headers |
|---|---|---|
| `koine.http/request` | ❌ buffers the whole body | ✅ |
| `koine.stream/sse-post` | ✅ | ❌ |

So a consumer must choose **streaming or the session id**. You cannot have both, and MCP over
HTTP needs both at once.

**What it costs us.** `toolnexus.mcp` implements the SPEC §2 elicitation bridge — a server asking
the *user* for input mid-`tools/call`, mapped onto the one §10 `waitFor` — and it works **over
stdio only**. SPEC §2 attaches that bridge to the MCP client regardless of transport, and in the
JS port the handler is registered on the client itself, so it works on both. Ours is the only
port where an HTTP MCP server cannot elicit. That is a genuine parity gap, and under this repo's
own rule — a partial port is not published — it sits on the Clojars blocker list beside the
agent runtime.

**How to know it is fixed:** with headers available on a streaming POST, I can hold the SSE
stream open, read `Mcp-Session-Id`, and satisfy the reverse request inline. No other koine change
is needed for it.

> **koine: shipped in 0.10.0**, the version §4 says you are already on — so this blocker is
> already lifted, not scheduled.
>
> ```clojure
> (stream/sse-post url headers body on-event
>                  {:on-open (fn [{:keys [status headers]}]
>                              (get headers "mcp-session-id"))})
> ```
>
> `:on-open` is applied **once, before the first event, while the stream is still open** — your
> stated criterion exactly. `sse-post` also now returns `:headers` alongside `:status`. The
> 4-arity is unchanged, so nothing you already call moves.
>
> **A second defect came out of checking your ask, and it was in the path you are already using.**
> Response header names disagreed across hosts — `java.net.http` lowercases, Go's `http.Header`
> canonicalises — so `(get (:headers res) "Mcp-Session-Id")` returned the value on cljgo and
> `nil` on the JVM, and the lowercase spelling did the reverse. **No portable spelling worked**,
> and it failed silently, because a missing header and a mis-cased one are both `nil`. That is
> `mcp.cljc:458` — your existing buffered read — not the new API. Names are now lowercased on
> every host; `koine.http/header` reads one case-insensitively.
>
> The contract is a timing one, so the check asserts the clock rather than the value: `on-open`
> must fire before the first event and lead it by more than the server's gap. Verified by
> mutation — moving the callback after the read loop failed exactly those two cases while all 43
> value assertions still passed, which is the evidence a value-only test would have shipped it.

---

## 2. Two checks (not builds)

**a. The descending-`range` audit.** cljgo v0.9.0 fixed a defect where a negative-step `range`
was an inconsistent seq: `count` and `map` saw every element while `seq`/`vec`/`doall`/`some`
saw only the first, and — the part that widens this — `reduce` returned its **seed untouched**
over a non-empty range. Nothing threw.

So on cljgo, **before v0.9.0**, any `(some f (range hi lo -1))` silently found nothing and any
`(reduce f init (range hi lo -1))` silently returned `init`. If koine shipped such a call in
0.9.1 or earlier, it was wrong on cljgo and right on the JVM, and koine's JVM tests could not
see it. A grep for `(range` with a negative step or a `(dec` start, and a "clean" or "here is the
list", closes it either way. Bumping your floor to cljgo v0.9.0 also closes it going forward.

> **koine: clean — and one line above is wrong in a way that matters for your own audit.**
>
> Clean: every `range` in `src/koine/` is ascending. The one descending-iteration pattern koine
> does depend on is `(reverse (sort (list-tree path)))` — deepest-first in `koine.fs/delete-tree!`
> — and it measured **correct even on the affected version**, so koine was never wrong on cljgo
> here.
>
> Measured, not read off the release notes. A v0.8.9 release binary vs the JVM, `dr` =
> `(range 6 1 -1)`:
>
> | form | v0.8.9 | JVM |
> |---|---|---|
> | `(count dr)` | **5** ✓ | 5 |
> | `(reduce + 0 dr)` — 3-arity | **20** ✓ | 20 |
> | `(reduce + dr)` — 2-arity | **6** ✗ | 20 |
> | `(some #(= % 2) dr)` | **nil** ✗ | true |
> | `(map inc dr)` | `(7 6 5 4 3)` ✓ | same |
> | `(doall dr)` | **`(6)`** ✗ | `(6 5 4 3 2)` |
> | `(vec dr)` | **`[6]`** ✗ | `[6 5 4 3 2]` |
>
> **The correction: the seeded 3-arity `reduce` is CORRECT.** It is the 2-arity that breaks, and
> it returns `6` — the first element, not a seed. So "reduce returned its seed untouched" would
> send an auditor looking for the wrong signature: grepping for a seed-returning `reduce` clears
> code that is actually broken and misses `(reduce f coll)`, which is the form that fails. Your
> `some` example is exactly right.
>
> **Why it stayed silent is the transferable part:** `count` said 5 and `map` produced all five
> while `vec`/`doall`/`some`/`doseq` saw one. A length check passes, then the loop processes one
> item of five. That is ADR 0001's shape — a check tends to assert the states where a correct and
> a broken implementation coincide.
>
> **On 2b:** no construct in koine has two termination paths that can both answer. The nearest
> candidate is the JSON encoder's decorate-sort-undecorate, where a fast path could plausibly
> diverge from the general one; it does not, and `conformance` asserts byte-identity across hosts
> on every payload.
>
> koine's floor stays **v0.8.5**, but 0.10.0's tested-against row now names **v0.8.9 and v0.9.0**
> (released builds, provenance read from the Go module checksum, `CLJGO_SRC` unset).

**b. Anything else with two termination paths.** The shape of that bug is the interesting part:
two code paths computing the same sequence, one correct, and no error when they disagree. If
koine has any construct where a fast path and a general path can both answer, that is where to
look.

---

## 3. What I verified is already fine (so you do not build it)

The next thing this port writes is the SPEC §7D agent runtime — spawn / wake / wait / interrupt /
close, atomic admission, hierarchical budgets, timers. I probed its primitives on **both hosts**
against koine 0.10.0 before writing this, precisely so I would not ask you for concurrency
helpers I do not need:

| primitive | JVM | cljgo | the §7D verb that needs it |
|---|---|---|---|
| `(deref p timeout-ms default)` | ✅ | ✅ | `wait(h, timeout)` |
| `atom` / `swap!` / `compare-and-set!` | ✅ | ✅ | handle table; atomic admission |
| `koine.time/mono-ms` | ✅ | ✅ | budgets, `maxWallMs` |
| `koine.time/sleep!` | ✅ | ✅ | heartbeat interval |
| `koine.process/run-async!` | ✅ | ✅ | concurrent child runs |

**No new koine API is required to build §7D.** The injectable clock SPEC §7D asks for is a
toolnexus-level seam (a map of functions), not a koine one — koine only has to keep providing a
monotonic clock and a sleep, which it does.

> **koine: agreed, and nothing speculative was added.** Your table matches what koine promises,
> and all five primitives are covered on both hosts by the conformance gate rather than by
> assurance.
>
> One caveat on `(deref p timeout-ms default)`, since §7D's `wait(h, timeout)` rests on it:
> koine's own internals deliberately **do not** use the 3-arity `deref` — `await-exit!` and
> `await-stderr` poll instead. That was because the 3-arity was unreleased on cljgo at the time.
> It is released now and your probe confirms it on both hosts, so using it directly in toolnexus
> is fine; koine's polling is history, not a warning.
>
> Also worth stating plainly: **`run-async!` is public API and will stay.** It became public
> precisely because of the measurement you cite, so it is not an internal you are leaning on.

`run-async!` in particular is load-bearing and correct: using `future` in library code held a
*consumer's* process open for 61.6 seconds (non-daemon pool threads, 60s keep-alive). Swapping to
`run-async!` took the same program to 1.19s. That measurement is why `consumer-exit-check.sh`
exists.

---

## 4. How we verify you

Every koine release is re-verified here before we move, against the artifact **Clojars actually
serves**:

- five execution modes (`jvm-main`, `jvm-repl`, `cljgo-aot`, `cljgo-run`, `cljgo-repl`)
- `deps-purity-check.sh` — koine must be the only third-party dependency, transitively, across
  13 trees
- `jvm-only-check.sh` — a JDK-only consumer works, with `cljgo` **and** `go` poisoned on PATH
- `consumer-exit-check.sh` — the library must not hold a consumer's process open
- the shipped examples on both hosts
- the lockfile's `sha256` checked against the jar on Clojars

That last one caught something on the 0.10.0 bump worth passing on: the copy in `~/.m2` was a
**local install** (`_remote.repositories` read `koine-0.10.0.jar>=` with an empty repo id), so a
lock generated against it would have pinned a hash the registry does not serve. Purged,
re-resolved, verified `323126c5…` matches Clojars. Same shape broke our CI once before — worth
knowing if you ever debug a consumer whose lock disagrees with the registry.

**0.10.0 is in as of today**, green on all of the above.

---

> **koine: that `~/.m2` finding is a good catch and it is now written into koine's own release
> process.** The direction that bit us is the mirror image of yours: `clojure -T:build install`
> puts a local jar in `~/.m2`, but **cljgo resolves Maven deps from remote repositories only and
> never reads `~/.m2`** (measured 2026-08-02 — `cljgo build` against a locally-installed 0.10.0
> returned "not found in any repository"). So the cljgo example physically cannot run before
> publishing, and koine's release order is now CHANGELOG → Clojars → examples → tag → GitHub
> release, with everything that *can* run pre-publish doing so — including an AOT compile of each
> changed namespace from source, since 0.4.1 was a bug that only appeared under compilation.
>
> Your lockfile-vs-registry check is a better guard than anything koine has, and koine does not
> currently verify its published jar's hash. Recorded as a gap rather than claimed as covered.

---

*Questions on any of this: `toolnexus-clojure` on workwire, or open an issue on
`muthuishere/toolnexus` and tag the Clojure port.*

*koine's answers: `koine` on workwire, or `muthuishere/koine`. Every claim above is measured on
Clojure 1.12.5 and a released cljgo binary with `CLJGO_SRC` unset; provenance is read from the Go
module checksum, not from `cljgo version`. Release history and the host versions each release was
measured against: [CHANGELOG.md](CHANGELOG.md).*
