# koine — in progress

Working state as of **2026-07-31**. Not a spec, not a promise — the honest
picture of what is done and what is not.

Read `README.md` for what koine *is*.

---

## TL;DR

**Published and consumable: `net.clojars.muthuishere/koine 0.9.1`.** Two hosts,
Clojure (JVM) and cljgo, both supported outright. Every seam is implemented on
both, every conformance check is green on both, and both example projects
consume the released artifact rather than the working tree.

`0.5.0` was the two-host release: **breaking** for anyone on the runtimes koine
used to carry, and it dropped `koine.host/tier` — with two hosts and one tier,
the var described nothing. `id`, `capabilities` and `supports?` remain.

`0.6.0` answers the toolnexus asks and fixes a 60-second hang that shipped in
both 0.4.2 and 0.5.0 (below). New: `sh` takes `:timeout-ms`, a spawned child
takes `kill!`, and `koine.fs` grows `mkdirs!` / `delete!` / `delete-tree!` /
`temp-dir!` so a consumer stops shelling out to `mkdir -p` and `rm -rf`.

`0.7.0` fixes a **cross-host divergence in koine's own API** — `alive?` answered
differently on the two hosts for a child that exited by itself — makes
`run-async!` public, and adds `shadow_check`. All three came from the toolnexus
port reporting them; see below.

---

## Where it stands

| gate | result |
|---|---|
| `clojure -M:test` | 109 tests, 372 assertions, 0 failures |
| `./run-conformance.sh` | 13 checks, green on JVM **and** cljgo |
| `./examples/run-both.sh` | both consumer projects agree, on the Clojars artifact |

Conformance per check, both hosts: conformance 14/14 · bytes 17/17 · codec 11/11 ·
env 12/12 · fs 39/39 · http 13/13 · mcp 5/5 · process 48/48 · route 43/43 ·
server 10/10 · shadow 1/1 · stream 34/34 · time 28/28.

`clojure -M:test` only proves the JVM. A change is not verified until
`run-conformance.sh` is green on both.

## Namespace status

Every namespace works on both hosts and has both a JVM unit suite and a
cross-host conformance check.

| ns | conformance |
|---|---|
| `koine.json` | `conformance` 14/14 |
| `koine.stream` | `stream_check` 34/34 |
| `koine.route` | `route_check` 43/43 |
| `koine.time` | `time_check` 28/28 |
| `koine.server` | `server_check` 10/10 |
| `koine.env` | `env_check` 12/12 |
| `koine.process` | `process_check` 48/48 + `mcp_check` 5/5 |
| `koine.http` | `http_check` 13/13 |
| `koine.fs` | `fs_check` 39/39 + `bytes_check` 17/17 |
| `koine.codec` | `codec_check` 11/11 |
| `koine.host` | used by every check to decide what to run |

## What `mcp_check` proves

`initialize` → `notifications/initialized` → `tools/list` → `tools/call` against
`npx -y @modelcontextprotocol/server-everything`, skipping the notifications the
server interleaves. It passes on the JVM, under `cljgo run`, and from a `cljgo
build` binary. This is the workload the library was justified by.

## The 60-second hang in 0.4.2 and 0.5.0

**Any program that called `koine.process/spawn` sat idle for 60 seconds after
finishing.** `spawn`'s stderr drain ran on `future`, and Clojure's future pool
threads are non-daemon with a 60-second keep-alive, so the JVM would not exit.
No output, no CPU, nothing to point at. Measured against the published 0.4.2
artifact, 2026-07-31: 61.07s for a spawn that did its work in 0.4s.

Fixed in 0.6.0 — background readers now run on an explicitly daemon thread
(`run-async!`). A library must never decide when the host program may exit, and
`(shutdown-agents)` is not something a library may demand of its consumer. The
same trap would have hit `sh` when it gained concurrent pipe readers.

## `alive?` meant two different things (fixed in 0.7.0)

**A child that exited on its own answered `alive? false` on the JVM and `true`
on cljgo.** One public function, two answers, inside the library whose entire
job is preventing that. `exited` was set only inside `close!`/`kill!`, so a peer
nobody had stopped was never marked dead. cljgo now runs a reaper that owns the
single permitted call to `:wait` and marks the child the moment it exits.

**`process_check` was structurally blind to it.** Every `alive?` case sat either
mid-conversation (child up, both hosts agree) or beside `kill!` (which is what
*sets* the exit on cljgo, so both agree again). Every tested case was one where
the two implementations coincide. The check was written around the MECHANISM
instead of the QUESTION — nobody had ever asked "is it running?" of a child
nobody had stopped. That is the failure mode to watch for in every other check
here. Reported by the toolnexus port; koine's green gate proved nothing.

## `run-async!` is public now

Keeping it private fixed the 60-second hang for koine and handed it straight to
consumers: a caller writing its own reader loop reaches for `future`, gets the
non-daemon pool, and cannot call `(shutdown-agents)` from library code either.
Measured by toolnexus on their MCP suite — 64.9s with `future`, 4.3s with the
pool shut down; cljgo 4.5s either way. Same defect, third independent path.

## Still open

- **`fs/real-path`** — accepted, not built: no canonicalisation seam, so a
  directory walk cannot guard a symlink CYCLE. Note `cljg.io/absolute` is `filepath.Abs`: it does not
  resolve symlinks and is not a substitute.
- **A YAML seam.** toolnexus needs one for SKILL.md frontmatter and cannot take
  a dependency. koine's position: YAML is not a spec you can pin the way JSON
  is, and a parser touches no host, so it fails koine's charter twice over. The
  recommendation instead is a *named subset* — call it `frontmatter`, not
  `yaml`, and make it THROW on anything outside the subset, because a silent
  misparse is the failure that matters. Unresolved.
- **Two `koine.process` changes**, deferred until toolnexus's transport
  namespace exists so a real caller shapes them:
  1. `send-line!` atomicity for concurrent writers.
  2. Distinguishing a settled exit code from a clean EOF after `read-line!`
     returns nil.

  The third — waking a blocked `read-line!` — is **closed by `kill!`**: killing
  the child closes its stdout, so the parked reader hits EOF and returns nil.
  One mechanism, both problems.
- **Pattern-based date formatting is deliberately absent.** Go layouts and
  java.time patterns are different languages and koine will not fake a
  translation. `iso-str` / `parse-iso` cover the wire format, which is what
  protocols carry.
- **cljgo cannot consume Clojars** (its ADR 0095 is proposed), so the cljgo
  example takes the artifact through its own resolver. One source, two
  coordinates until 0095 lands.
- **Is `koine.route` / `koine.server` in scope?** They sit outside the seam
  items the library was justified by and they widen the frozen surface. Shipped
  rather than held back, but flagged unstable in the README — the cheap
  correction later is to move them to a second artifact.

## Open cljgo issues koine filed

`#166` (`run resolve -update` does not exist — CLI design call), `#167`
(`load-file` resolves but is unbound), `#168` (fresh clone + `cljgo run` gives a
bare require failure instead of "no lock"), **`#169`** (two `exe` calls in one
build.cljgo ship a corrupt second binary — both faces reproduced),
**`#170`** (`cljgo version` always reports `0.1.0-dev`), `#171` (cljgo's
`clojure.core` has vars the JVM's lacks — `ok`, `err`).

Fixed upstream with tests rather than filed: the `.cljc` test-walk skip
(released in v0.8.2), the byte-boundary emit truncation, `bri` serve printing to
stdout, and `deref` missing its 3-arity.

## Release mechanics

`build.clj` builds a **source-only** jar — no `compile-clj`, since consumers
must read the `.cljc`. `deps.edn`'s `:build` alias carries tools.build and
deps-deploy; those are release-time only, so the library still has zero
third-party deps on a consumer's classpath. License is MIT. Group
**`net.clojars.muthuishere`** — Clojars pre-verifies `net.clojars.<user>`,
whereas `io.github.<user>` needs a one-time GitHub verification this account has
not done (deploy under it 403s with "Group … doesn't exist"). Deploy reads
`CLOJARS_USERNAME` / `CLOJARS_PASSWORD` from the environment — a deploy token,
never a file in this repo.

Do **not** use `cljgo publish clojars`: it never contacts clojars.org, it writes
a git-coordinate stub.
