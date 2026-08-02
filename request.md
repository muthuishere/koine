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

*Questions on any of this: `toolnexus-clojure` on workwire, or open an issue on
`muthuishere/toolnexus` and tag the Clojure port.*
