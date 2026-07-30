# koine — in progress

Working state as of **2026-07-30 (night)**. Not a spec, not a promise — the
honest picture of what is done and what is not.

Read `README.md` for what koine *is*.

---

## TL;DR

**The four steps this file listed as "before the first Clojars release" are
done.** All four cljgo blockers are implemented and verified, the untested
host-touching namespaces have tests, and a real MCP handshake runs through
`process/spawn` on both supported hosts — interpreted and as an AOT binary.
`0.1.0` shipped; **`0.2.0` follows** with the round-two cljgo gaps closed —
byte I/O, ISO-8601 dates and base64 are now koine seams, all filed upstream as
cljgo ADR 0110 and implemented there rather than worked around here.

---

## What was blocking, and what closed it

cljgo shipped Clojure-shaped APIs for every gap, so **not one koine branch needs
`require-go`**. That matters more than it sounds: raw interop only links under
`cljgo build`, and a host value on that path rides cljgo's nil-substituting
discovery pass, where `(.StdinPipe cmd)` dies at *build* time on nil. Going
through `cljg.*` sidesteps the whole class of failure — the branches are portable
Clojure, identical under `cljgo run` and `cljgo build`.

| # | blocker | closed by | verified |
|---|---|---|---|
| 1 | no environment access | `cljg.system/getenv` | `env_check` 12/12 both hosts |
| 2 | no streaming subprocess | `cljg.process/spawn` + `cljg.stream` | `process_check` 16/16, `mcp_check` 5/5 |
| 3 | no streaming HTTP body | `cljg.net.http` `:as :stream` | `stream_check` 29/29 — arrivals 152/303/458/611 ms, genuinely incremental |
| 4 | no monotonic clock | `cljg.date/nano-time` | `time_check` 14/14; the wall-clock clamp is gone on cljgo |

Two bugs surfaced while proving it, both fixed:

- **`koine.env/expand` was broken on cljgo** — it used `str/replace` with a
  *function* replacement, which cljgo rejects (`replace expects a String, got:
  #object[fn]`). Rewritten as a plain scan, so it is now pure `clojure.core`.
- **`koine.json` warned on every cljgo load** — a private `err` shadowed
  `clojure.core/err`. Renamed `parse-err`.

`koine.time/sleep!` on cljgo no longer parks on a `core.async` timeout channel;
it calls `cljg.system/sleep`.

---

## Namespace status

JVM suite: **100 tests, 338 assertions, 0 failures** (`clojure -M:test`) — was
41/181. Conformance: every check passes on both supported hosts, and every one
also passes from a `cljgo build` binary.

| ns | JVM | cljgo | unit tests | conformance |
|---|---|---|---|---|
| `koine.json` | ✅ | ✅ | ✅ | `conformance` 9/9 |
| `koine.stream` | ✅ | ✅ | ✅ | `stream_check` 29/29 |
| `koine.route` | ✅ | ✅ | ✅ | `route_check` 43/43 |
| `koine.time` | ✅ | ✅ | ✅ | `time_check` 14/14 |
| `koine.server` | ✅ | ✅ | ✅ | `server_check` 10/10 |
| `koine.env` | ✅ | ✅ | ✅ **new** | `env_check` 12/12 **new** |
| `koine.process` | ✅ | ✅ | ✅ **new** | `process_check` 16/16 + `mcp_check` 5/5 **new** |
| `koine.http` | ✅ | ✅ | ✅ **new** | `http_check` 2/2 |
| `koine.fs` | ✅ | ✅ | ✅ **new** | `fs_check` 19/19 **new** |

The gap this file called "the single biggest argument against publishing" — no
tests at all on the four host-touching namespaces — is closed.

`src/env_check.cljc` was previously named `envcheck.cljc`, which
`run-conformance.sh`'s `*_check.cljc` glob never matched, so it had never run in
the suite.

## What `mcp_check` proves

`initialize` → `notifications/initialized` → `tools/list` → `tools/call` against
`npx -y @modelcontextprotocol/server-everything`, skipping the notifications the
server interleaves. It passes on the JVM, under `cljgo run`, and from a 14 MB
`cljgo build` binary. This is the workload the library was justified by, and it
had never run before today.

---

## Consumer verification

Both supported hosts were driven as real *consumers*, not just as test hosts:

- **JVM** — `clojure -T:build install`, then a separate project depending on
  `net.clojars.muthuishere/koine {:mvn/version "0.1.0"}` from `~/.m2`: spawn +
  JSON + env + monotonic clock all live.
- **cljgo** — a separate project declaring `(dep b "koine" {:path …})`, both
  `cljgo run` and `cljgo build`; the installed binary produces the same result.

---

## Still open

- **Pattern-based date formatting is deliberately absent** — Go layouts and
  java.time patterns are different languages and koine will not fake a
  translation. `iso-str` / `parse-iso` cover the wire format, which is what
  protocols carry. Byte I/O and base64 are now IN (0.2.0).
- **cljgo cannot consume Clojars** (its ADR 0095 is proposed), so cljgo users
  take the git coordinate. One source, two coordinates until 0095 lands.
- **Is `koine.route` / `koine.server` in scope?** They sit outside the four seam
  items the library was justified by and they widen the frozen surface. Shipped
  in `0.1.0` rather than held back, but flagged unstable in the README — the
  cheap correction later is to move them to a second artifact.
- **Tier 2/3 hosts have no subprocess route**, so `process_check`, `fs_check` and
  `mcp_check` do not run on Glojure or let-go. Informational only (`PORTING.md`).

## Release mechanics (done)

`build.clj` builds a **source-only** jar — no `compile-clj`, since consumers must
read the `.cljc`. `deps.edn` gained a `:build` alias carrying tools.build and
deps-deploy; those are release-time only, so the library still has zero
third-party deps on a consumer's classpath. License is MIT (`LICENSE`, in the
pom). Group **`net.clojars.muthuishere`** — Clojars pre-verifies
`net.clojars.<user>` for every account, whereas `io.github.<user>` needs a
one-time GitHub verification this account has not done (deploy under
`io.github.muthuishere` 403s with "Group … doesn't exist"). Deploy reads `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` from the
environment — a deploy token, never a file in this repo.

Do **not** use `cljgo publish clojars`: it never contacts clojars.org, it writes
a git-coordinate stub.
