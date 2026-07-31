# koine — in progress

Working state as of **2026-07-31**. Not a spec, not a promise — the honest
picture of what is done and what is not.

Read `README.md` for what koine *is*.

---

## TL;DR

**Published and consumable: `net.clojars.muthuishere/koine 0.5.0`.** Two hosts,
Clojure (JVM) and cljgo, both supported outright. Every seam is implemented on
both, every conformance check is green on both, and both example projects
consume the released artifact rather than the working tree.

`0.5.0` is the two-host release. It is **breaking** for anyone who was on the
runtimes koine used to carry, and it drops `koine.host/tier` — with two hosts
and one tier, the var described nothing. `koine.host/id`, `capabilities` and
`supports?` remain, and `supports?` is still the supported way to degrade
without a host-specific `catch`.

---

## Where it stands

| gate | result |
|---|---|
| `clojure -M:test` | 109 tests, 372 assertions, 0 failures |
| `./run-conformance.sh` | 12 checks, green on JVM **and** cljgo |
| `./examples/run-both.sh` | both consumer projects agree, on the Clojars artifact |

Conformance per check, both hosts: conformance 9/9 · bytes 17/17 · codec 11/11 ·
env 12/12 · fs 19/19 · http 9/9 · mcp 5/5 · process 20/20 · route 43/43 ·
server 10/10 · stream 34/34 · time 28/28.

`clojure -M:test` only proves the JVM. A change is not verified until
`run-conformance.sh` is green on both.

## Namespace status

Every namespace works on both hosts and has both a JVM unit suite and a
cross-host conformance check.

| ns | conformance |
|---|---|
| `koine.json` | `conformance` 9/9 |
| `koine.stream` | `stream_check` 34/34 |
| `koine.route` | `route_check` 43/43 |
| `koine.time` | `time_check` 28/28 |
| `koine.server` | `server_check` 10/10 |
| `koine.env` | `env_check` 12/12 |
| `koine.process` | `process_check` 20/20 + `mcp_check` 5/5 |
| `koine.http` | `http_check` 9/9 |
| `koine.fs` | `fs_check` 19/19 + `bytes_check` 17/17 |
| `koine.codec` | `codec_check` 11/11 |
| `koine.host` | used by every check to decide what to run |

## What `mcp_check` proves

`initialize` → `notifications/initialized` → `tools/list` → `tools/call` against
`npx -y @modelcontextprotocol/server-everything`, skipping the notifications the
server interleaves. It passes on the JVM, under `cljgo run`, and from a `cljgo
build` binary. This is the workload the library was justified by.

## Still open

- **Three `koine.process` changes requested by toolnexus**, deferred until their
  transport namespace exists so the API is shaped by a real caller:
  1. `send-line!` atomicity for concurrent writers.
  2. A killable / interruptible `read-line!` on `close!` — today a blocked
     reader is not woken.
  3. Distinguishing a settled exit code from a clean EOF after `read-line!`
     returns nil.
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
(`load-file` resolves but is unbound), `#168`. Two other defects found by
consuming koine were fixed upstream with tests rather than filed: the `.cljc`
test-walk skip (released in v0.8.2) and the byte-boundary emit truncation.

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
