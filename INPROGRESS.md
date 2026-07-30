# koine — in progress

Working state as of **2026-07-30**. Not a spec, not a promise — the honest
picture of what is done, what is not, and what has to happen before the first
Clojars release.

Read `README.md` for what koine *is*. This file is for whoever is about to
publish it.

---

## TL;DR

**koine works and is worth publishing — but not today, and not as `1.0.0`.**

The four host capabilities that make it useful on cljgo were **blocked
upstream** until three days ago. They are now **unblocked but unimplemented
here**, and the namespace most affected — `koine.process` — is the one whose
API is most likely to change as a result. Publishing now would freeze an
interface before the only workload that exercises it has ever run.

Recommended: implement the cljgo branches → run the MCP handshake against them
→ publish `0.1.0` with the API explicitly marked unstable.

---

## What changed upstream (2026-07-30)

cljgo **ADR 0104** — "`require-go` reaches the Go standard library" (renumbered
from 0096, which collided with the contrib-tier1 ADR) — is **implemented and
proven**, cljgo commit `4423d12`.

The gate for cljgo's zero-bindings AOT interop route changed from
`isThirdPartyGoPath` (does the first path segment contain a dot?) to "declared
via `require-go`". That admits the Go standard library. Proven end to end by
three spikes that build and run real AOT binaries:

| spike | proves | koine issue closed |
|---|---|---|
| S56 | `os.Getenv` in a 6.7 MB AOT binary | **1** — env |
| S57 | `exec.Command` + `StdinPipe`/`StdoutPipe`, long-lived child, two round trips | **2** — streaming subprocess (this *is* MCP stdio) |
| S58 | `resp.Body` read line-by-line via `bufio`, body still open; `time.Now`/`Since`/`Sleep` | **3** — streaming HTTP · **4** — monotonic clock |

See `docs/cljgo-requests.md` for the koine-side statement of need, now updated.

### Three things this bought us, and one it cost

Two ADR claims were **retracted** on re-measurement, so the honest blocker count
was four, not nine:

- `clojure.core` privates *are* reachable fully-qualified — the "inconsistent
  visibility" issue was backwards.
- `cljg.io` already has `mkdirs`, `move!`, `stat`, `size`, `temp-dir`. Only
  binary `read-bytes`/`write-bytes` is genuinely missing, so crash-safe write
  via rename works on cljgo today.

And one **new constraint that koine's cljgo branches must respect**:

> cljgo's AOT discovery pass evaluates your Clojure with `nil` substituted for
> every host result. A nil-intolerant pure function applied to a host value
> therefore fails at **build** time, not run time —
> `(clojure.string/trim (.ReadString! rdr 10))` dies with
> *"trim expects a string, got: nil"*.

Every cljgo branch written from here must keep host results on a nil-tolerant
path. This is the most likely source of future "works in `cljgo run`, fails in
`cljgo build`" reports.

Minor: `lang.Char` does not coerce to Go `byte` — pass the integer (`10`, not
`(char 10)`).

**None of this is released.** cljgo needs a release carrying `4423d12` before
koine's cljgo branches can stop throwing.

---

## Namespace status

JVM suite: **41 tests, 181 assertions, 0 failures** (`clojure -M:test`).
JSON conformance: **9/9 on all four hosts**.

| ns | JVM | cljgo | tests? | note |
|---|---|---|---|---|
| `koine.json` | ✅ | ✅ | ✅ `json_test` | pure `clojure.core`, no host code. The strongest thing in the library |
| `koine.stream` | ✅ | ✅ | ✅ `stream_test` | pure |
| `koine.route` | ✅ | ✅ | ✅ `route_test` | pure |
| `koine.time` | ✅ | branch present | ✅ `time_test` | cljgo branch unverified on host |
| `koine.server` | ✅ | branch present | ✅ `server_test` | |
| `koine.env` | ✅ | ⚠️ **throws** | ❌ **none** | unblocked by 0104 (`os.Getenv`); not yet written |
| `koine.process` | ✅ | ⚠️ **throws** | ❌ **none** | unblocked by 0104 (`exec.Cmd.StdinPipe`); **API most likely to move** |
| `koine.http` | ✅ | branch present | ❌ **none** | streaming unblocked by 0104 (`resp.Body`) |
| `koine.fs` | ✅ | branch present | ❌ **none** | |

### The gap that matters most for publishing

**The four host-touching namespaces — `env`, `process`, `http`, `fs` — have no
test files at all.** Everything under `test/` covers the pure namespaces
(`json`, `route`, `stream`, `time`, `server`). So the parts of koine that
justify its existence are the parts with the least coverage.

That is the single biggest argument against publishing right now. A portability
library whose *portability layer* is untested is selling the one thing it hasn't
verified.

---

## Before the first Clojars release

In order. Each step de-risks the next.

1. **Implement the cljgo branches** now that 0104 unblocks them:
   `env/get-env` (`os.Getenv` — remember Go returns `""` where the JVM returns
   `nil`, and `""` is truthy in Clojure; `koine.env` already normalises this on
   other hosts, keep it), `process/spawn` (`exec.Cmd.StdinPipe`/`StdoutPipe`),
   `http` streaming (`resp.Body`), `time` monotonic (`time.Since`).
   Keep every branch **nil-tolerant** per the constraint above.
2. **Write tests for `env`, `process`, `http`, `fs`** — the current hole.
3. **Run a real MCP handshake through `process/spawn` on both hosts**
   (toolnexus spike S11: `initialize` → `notifications/initialized` →
   `tools/list` → `tools/call` against
   `npx -y @modelcontextprotocol/server-everything`). This is the workload
   `spawn` exists for, and it has never run. If the API is wrong, this is what
   tells you.
4. **Only then freeze the API and cut `0.1.0`.**

Steps 1–3 are exactly why toolnexus ADR 0009 §3b said *"prove, then extract —
not the reverse"*: publishing to Clojars freezes the interface, and changing it
afterwards costs a breaking release.

---

## Publishing to Clojars — the mechanics

All feasible; nothing here is blocked. koine is a **source-only** artifact
(`.cljc`, no AOT, no Java), which is the easy case.

**Not yet present in this repo:** there is no `build.clj`. That has to be
written before any of the below runs.

### 1. Group verification

Use **`io.github.muthuishere`** — verifiable through the GitHub account of the
same name, and consistent with how the toolnexus Java port is published
(`io.github.muthuishere:toolnexus`). Clojars verifies `io.github.<user>` and
`net.clojars.<user>` groups. Coordinate: `io.github.muthuishere/koine`.

### 2. `build.clj` (tools.build)

Jar carrying the `.cljc` source plus a pom. Needs: `:lib`, `:version`,
`:scm` pointing at `git@github.com:muthuishere/koine.git`, a license
(pick one — the repo currently declares none, and Clojars/consumers expect it),
and `:src-dirs ["src"]`. No `compile-clj` step — do **not** AOT; the whole point
is that consumers read the `.cljc`.

### 3. Credentials

A Clojars **deploy token**, in the environment only:

```bash
# never commit these, never echo the value
export CLOJARS_USERNAME=muthuishere
export CLOJARS_PASSWORD=$(…deploy token from your secret store…)
clojure -T:build deploy
```

Treat the token exactly like every other registry token in this workspace:
use-only, referenced by name, never written into code, config, CI logs or a
committed file.

### 4. cljgo consumption

cljgo **cannot consume from Clojars** (ADR 0095 is proposed, not shipped), so
cljgo users take the *same source tree* by **git coordinate**. One source, two
coordinates, until 0095 lands. Document both in the README at release time.

Do **not** use `cljgo publish clojars` — its validator rejects Go interop and it
emits a git-coordinate stub rather than a real Clojars artifact.

### 5. Version policy

Cut **`0.1.0`**, and say plainly in the README that the API is unstable while
the cljgo branches settle. `koine.process` in particular may change shape once
step 3 above has run for real.

---

## Open questions

1. **License.** The repo declares none. Clojars deployment effectively needs
   one; consumers definitely do. EPL-1.0 is the Clojure-ecosystem default.
2. **Is `koine.route` / `koine.server` in scope?** The README says koine is
   deliberately narrow — "only what touches the host… the floor, not an app
   framework." nginx-style routing and a reverse proxy sit well outside the four
   seam items the library was justified by. They may be excellent, but they
   widen the API you are about to freeze. Consider shipping them as a separate
   artifact, or holding them out of `0.1.0`.
3. **Does toolnexus depend on published koine or a git coordinate first?**
   Recommend `:local/root` during development, git coordinate for CI, and the
   Clojars coordinate only after step 4 above.
