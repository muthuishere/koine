(ns koine.process
  "Subprocesses, portable.

  `sh` runs a command to completion; `spawn` keeps a long-lived child with piped
  stdin/stdout, which is what an MCP stdio transport needs and what `sh` cannot
  express.

  Both have an OFF SWITCH: `sh` takes `:timeout-ms`, and a spawned child takes
  `kill!`. A subprocess is the most dangerous thing most programs do, and one
  that cannot be stopped is a program that cannot be stopped."
  ;; `close!` is in cljgo's clojure.core and NOT in the JVM's. Excluding it makes
  ;; the shadow DELIBERATE and declared rather than accidental — which is the
  ;; whole difference between this and the two that bit koine before
  ;; (`koine.json/err` warned on every cljgo load; `koine.route/proxy` had its
  ;; namespace rejected outright and forced the breaking 0.3.0 rename). The JVM
  ;; accepts excluding a name its core does not have, so one form covers both.
  (:refer-clojure :exclude [close!])
  (:require [clojure.string :as cstr]
            [koine.time :as ktime])
  #?(:cljgo (:require [cljg.io :as cio])))

;; cljgo: cljg.process (streaming spawn) and cljg.stream (the pipe handles) must
;; be interned before `spawn` is reachable. A top-level reader conditional with
;; no branch for the other hosts reads as nothing there, so no other dialect
;; pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.process] '[cljg.stream]))

;; ------------------------------------------------- helpers for the Go host
;;
;; :dir and :env are applied by wrapping the command in `sh -c` on hosts that
;; cannot set them on the child directly, so the quoting helper below is shared
;; rather than written twice.

(defn run-async!
  "Run `f` on a background thread that must NOT keep the program alive.

  NOT `future` on the JVM. Clojure's future pool threads are non-daemon with a
  60-second keep-alive, so a program that called ONE koine.process fn sat there
  for a full minute after printing its answer — no output, no CPU, nothing to
  see. `(shutdown-agents)` fixes it, but a library cannot demand that of a
  consumer, and a library must never decide when the host program may exit.
  Measured 2026-07-31: 60.5s for a `sh [\"true\"]`.

  On cljgo the equivalent is a goroutine, which never holds up exit.

  PUBLIC, and that is the point. This was private while koine used it to fix its
  own 60-second hang, which quietly handed the same hang to every consumer: a
  caller running its own reader loop over `read-line!` reaches for `future`,
  gets the non-daemon pool, and cannot call `(shutdown-agents)` from library
  code either. Measured by the toolnexus port on their MCP suite — 64.9s with
  `future`, 4.3s with the pool shut down, cljgo 4.5s either way. Fixing that
  only inside koine was fixing it for koine, not for anyone using koine.

  Returns immediately. `f` takes no arguments; its value is discarded, so
  communicate through an atom or a promise. Nothing waits for it — if you need
  to know it finished, deliver a promise at the end of `f`."
  [f]
  #?(:clj   (doto (Thread. ^Runnable f) (.setDaemon true) (.start))
     :cljgo (future (f))
     :default (throw (ex-info "koine.process: no async primitive for this host; add a branch in koine/process.cljc" {}))))

(defn- await-thunk
  "Call `f` until it returns non-nil, or `ms` elapses. Returns that value or nil.

  Polling rather than `(deref p ms default)`: the 3-arity blocking deref only
  reached cljgo main on 2026-07-31 and is not in any release, so depending on it
  would demand consumers build cljgo from source. A 5 ms poll costs nothing next
  to a subprocess and works on every host koine supports today."
  [f ms]
  (let [deadline (+ (ktime/mono-ms) ms)]
    (loop []
      (or (f)
          (when (< (ktime/mono-ms) deadline)
            (ktime/sleep! 5)
            (recur))))))

(defn- await-val
  "Poll the atom `a` until it holds a non-nil value, or `ms` elapses."
  [a ms]
  (await-thunk (fn [] @a) ms))

(defn- await-atom
  "True once `a` holds a non-nil value; false if `ms` elapses first."
  [a ms]
  (some? (await-val a ms)))

(defn- shq
  "Single-quote `s` for POSIX sh: wrap it, and end/escape/reopen each embedded
  quote. Nothing inside survives as a metacharacter, so a path or an env value
  containing a space, a `$` or a `;` cannot become a second command."
  [s]
  (str "'" (cstr/replace (str s) "'" "'\\''") "'"))

(defn- wrap-cmd
  "The argv for `sh -c`, applying `dir` and `env` as shell built-ins. Returns
  nil when neither is needed, so the common case execs the binary directly."
  [command dir env]
  (when (or dir (seq env))
    (let [parts (concat (when dir [(str "cd " (shq dir) " &&")])
                        (map (fn [[k v]] (str (name k) "=" (shq v))) env)
                        (map shq command))]
      ["sh" "-c" (cstr/join " " parts)])))

(defn sh
  "Run `command` (a vector) to completion. Returns
  {:out :err :exit :timed-out?}. Never throws on a non-zero exit — that is a
  normal result.

  opts: :in (string on stdin) :dir :env :timeout-ms

  `:timeout-ms` force-kills the command after n milliseconds. A killed command
  reports `:timed-out? true` and `:exit nil` — NOT a number. There is no exit
  code when a process is killed: the JVM would report 137 and Go -1, both of
  them inventions, and a caller doing `(zero? (:exit r))` must not silently read
  a kill as a clean run. `:timed-out?` is always present, so it is safe to test
  on every result. `:out` / `:err` carry whatever the command managed to write
  before it died, which is best-effort and NOT guaranteed to match across hosts.

  Without `:timeout-ms` the command runs to completion, however long that is.
  Asked for by the toolnexus port: an agent's `bash` tool needs an off switch."
  ([command] (sh command {}))
  ([command {:keys [in dir env timeout-ms]}]
   #?(:clj
      (let [pb (ProcessBuilder. ^java.util.List (vec (map str command)))
            _  (when dir (.directory pb (java.io.File. ^String dir)))
            _  (when env (let [m (.environment pb)]
                           (doseq [[k v] env] (.put m (str k) (str v)))))
            p  (.start pb)]
        (when in
          (with-open [os (.getOutputStream p)]
            (.write os (.getBytes ^String in "UTF-8"))))
        ;; Both pipes are drained CONCURRENTLY with the wait. Reading them in
        ;; sequence deadlocks the moment a command fills one buffer while we
        ;; block on the other — the same pipe-buffer trap `spawn` documents for
        ;; stderr, and with a timeout it would also make the deadline unreachable.
        (let [out-p (promise)
              err-p (promise)
              _     (run-async! (fn [] (deliver out-p (slurp (.getInputStream p)))))
              _     (run-async! (fn [] (deliver err-p (slurp (.getErrorStream p)))))
              done? (if timeout-ms
                      (.waitFor p (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                      (do (.waitFor p) true))]
          (if done?
            {:out @out-p :err @err-p :exit (.exitValue p) :timed-out? false}
            (do (.destroyForcibly p)
                (.waitFor p)
                ;; the readers end at EOF once the child is gone, but a
                ;; DEADLINE is still required: a grandchild holding the pipe
                ;; open outlives its parent, and this must not become the
                ;; second thing that hangs forever.
                {:out (deref out-p 2000 "") :err (deref err-p 2000 "")
                 :exit nil :timed-out? true}))))

      :cljgo
      (if-not timeout-ms
        ;; no deadline: `exec` is the exact-bytes path and stays the default
        (let [r (cio/exec (vec (map str command)) (cond-> {}
                                                    in  (assoc :in in)
                                                    dir (assoc :dir dir)
                                                    env (assoc :env env)))]
          {:out (:out r) :err (:err r) :exit (:exit r) :timed-out? false})

        ;; A DEADLINE MUST BOUND THE CALL, not just the report.
        ;;
        ;; `cljg.io/exec` takes :timeout-ms and does kill the process, but Go's
        ;; exec.Cmd.Wait also waits for stdout/stderr copying to finish — and a
        ;; GRANDCHILD inherits the pipe and holds the write end open after its
        ;; parent is killed. So the call returned :timed-out? true, correctly,
        ;; five seconds late. Measured 2026-07-31 with
        ;; `sh -c "sleep 5; echo x"` at a 300 ms deadline: jvm 314 ms,
        ;; cljgo 5008 ms. Same result map, 16x the wall clock — the map matched
        ;; so conformance passed, and the ONE feature whose entire purpose is
        ;; bounding time was not bounding it. Reported by the toolnexus port.
        ;;
        ;; So the deadline path drives the child directly and never waits on a
        ;; drain it does not control. Filed upstream too: this is every cljgo
        ;; caller of `exec`, not just koine's.
        (let [p    (cljg.process/spawn (vec (map str command))
                                       (cond-> {}
                                         dir (assoc :dir dir)
                                         env (assoc :env env)))
              out-a (atom nil) err-a (atom nil) exited (atom nil)]
          (when in (cljg.stream/write (:in p) (str in)))
          (cljg.stream/close (:in p))
          ;; read-all, not a line loop: `sh` returns exact bytes, and joining
          ;; lines would invent or drop a trailing newline.
          (run-async! (fn [] (reset! out-a (cljg.stream/read-all (:out p)))))
          (run-async! (fn [] (reset! err-a (cljg.stream/read-all (:err p)))))
          (run-async! (fn [] (reset! exited ((:wait p)))))
          (let [done? (await-atom exited timeout-ms)]
            (if done?
              ;; finished in time: the drains are at EOF or about to be, so a
              ;; short grace is enough and cannot become the new hang
              {:out (or (await-val out-a 2000) "") :err (or (await-val err-a 2000) "")
               :exit @exited :timed-out? false}
              (do ((:kill p))
                  ;; NO grace here, deliberately. `read-all` only returns at
                  ;; EOF, and the grandchild still holding the pipe is exactly
                  ;; why EOF never comes — so any wait is time spent to learn
                  ;; nothing. Granting 2000 ms per stream turned a 300 ms
                  ;; deadline into 4309 ms measured. Output on the timeout path
                  ;; is best-effort, as the docstring says, and "" is the honest
                  ;; answer when the writer is gone.
                  {:out (or @out-a "") :err (or @err-a "")
                   :exit nil :timed-out? true})))))

      :default
      (throw (ex-info "koine.process/sh: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))


;; ------------------------------------------------------------------- Child
;;
;; A child is a PLAIN MAP of closures — {:send-line! :read-line! :alive?
;; :close!} — and the four fns below just apply them. It was a `defprotocol` +
;; `reify` until 2026-07-31, and a host turned up with `defprotocol` but no
;; `reify`/`deftype`/`defrecord`/`extend-type` at all, so the protocol could be
;; declared there and never implemented.
;;
;; A map of closures is the portable object: it needs nothing but `fn` and
;; `get`. koine.server's handle already works this way for the same reason, and
;; so does cljgo's own cljg.process/spawn. The public API is unchanged —
;; `(send-line! child "x")` reads and behaves exactly as before.

(defn send-line!
  "Write `s` + newline to the child's stdin and flush. Returns nil."
  [child s] ((:send-line! child) s))

(defn read-line!
  "Block for one line from the child's stdout, WITHOUT the terminator.
  nil at EOF."
  [child] ((:read-line! child)))

(defn alive?
  "True while the child is running."
  [child] ((:alive? child)))

(defn close!
  "Close the child's stdin, wait for it to exit, and return the exit code.

  This is the POLITE shutdown and it WAITS: a child that ignores its stdin
  closing will hang here forever. When you need a guarantee rather than a
  request, use `kill!`."
  [child] ((:close! child)))

(defn kill!
  "Force-terminate the child. Returns nil — always, on both hosts.

  Returns nil rather than an exit code on purpose: a killed process did not
  choose one. The JVM would report 137 and Go -1, and koine does not invent
  agreement between two host-specific numbers. Ask `alive?` if you need to know
  it is gone.

  This is also the way OUT of a blocked `read-line!`. A reader parked on a hung
  peer cannot be interrupted portably, but killing the child closes its stdout,
  so the parked `read-line!` hits EOF and returns nil. One mechanism ends a
  runaway command and frees a stuck transport — asked for by the toolnexus port
  as two separate things, 2026-07-31."
  [child] ((:kill! child)))

(defn exit-code
  "The status the child exited with, or nil if it has NOT BEEN SEEN to exit yet.

  Read that second clause carefully, because it is not the same as \"the child
  is running\". Reaping is ASYNCHRONOUS on both hosts: a child can be dead while
  this still answers nil, for as long as it takes the host to notice. A caller
  that treats one nil as proof of life has a race, and it is the nastiest kind —
  it passes constantly on a fast machine and fails on a loaded one.

  So a single read is never a verdict. Poll to a deadline:

      (when (nil? (proc/read-line! c))
        (let [code (loop [n 0]
                     (or (proc/exit-code c)
                         (when (< n 50)
                           (ktime/sleep! 20)
                           (recur (inc n)))))]
          (if code
            (peer-died code)      ; it exited, and `code` says how
            (still-quiet c))))    ; still nothing after the deadline

  (koine's own `process_check` has always polled like this; an earlier version
  of this docstring told callers to branch on a single read, which is advice
  koine did not follow itself. Corrected on cljgo's prompting, 2026-08-01.)

  `if-let` on the result is safe from the usual trap: exit status 0 is TRUTHY in
  Clojure, unlike C or a shell, so a clean exit does not read as \"no exit\".

  This is the difference between a peer that DIED and one that went QUIET, which
  `read-line!` alone cannot tell you — it returns nil for both. Without it the
  only recourse is a timeout: wait a while, then kill the child to find out
  whether it was ever going to answer. That is a guess standing in for a fact,
  wrong in both directions — too short kills a slow peer, too long hangs on a
  dead one. Asked for by the toolnexus port, which was carrying exactly that
  guess in shipped code.

  A child killed by `kill!` HAS exited, so a status exists afterwards; it is
  whatever the host recorded for a signalled process, and the two hosts do not
  agree on that number. Do not read meaning into it — that is also why `kill!`
  itself returns nil.

  Most callers want `await-exit!` instead — see below."
  [child] ((:exit-code child)))

(defn stderr-lines
  "The child's most recent stderr lines (up to 200), oldest first, as a vector.

  Never blocks — stderr is drained from the moment the child starts, so this is
  a snapshot of a buffer someone else is filling.

  Which is exactly why `[]` DOES NOT MEAN the child wrote nothing. It means
  nothing has arrived HERE YET. The drain runs on a background thread, so it is
  eventually consistent: a child can have written plenty and exited, and this
  can still be empty for as long as the drain takes to catch up. Reading it once
  and concluding \"no stderr\" is the same race as reading `exit-code` once and
  concluding \"still running\".

  If you are collecting a crash report, poll until it settles:

      (loop [n 0]
        (let [e (proc/stderr-lines c)]
          (if (or (> n 50) (seq e))
            e
            (do (ktime/sleep! 20) (recur (inc n))))))

  koine's own `process_check` has always polled like that, and an earlier
  version of this docstring said `[]` meant the child had written nothing —
  advice koine did not follow itself. Found by applying cljgo's generalisation
  of the same defect in `exit-code`: wait on a callback, then assert on state
  published after it. 2026-08-01."
  [child]
  (if-let [f (:stderr-lines child)] (f) []))

;; ------------------------------------------------ waiting, as API not advice
;;
;; `exit-code` and `stderr-lines` are honest non-blocking snapshots, and both
;; have a nil/empty answer that means "not yet" rather than "no". Twice koine
;; shipped a docstring telling callers to poll around that, and twice the
;; docstring was wrong while the library and its tests were right — cljgo caught
;; the first, and koine found the second only by applying cljgo's generalisation
;; to itself.
;;
;; Advice is the weakest possible fix: nothing verifies it, and a consumer who
;; does not read it writes the race. So the polling loop those docstrings
;; described is now a FUNCTION, tested like everything else. The snapshots stay
;; for callers running their own loop; the correct thing is the easy thing.

(def ^:private settle-ms
  "Default deadline for the `await-*` fns. Long enough for a host to reap a
  child that has already exited, short enough that a caller who guessed wrong
  finds out quickly."
  1000)

(defn await-exit!
  "Wait up to `ms` (default 1000) for the child to be seen to exit, and return
  its status — or nil if the deadline passes first.

  This is what a transport wants after `read-line!` returns nil:

      (when (nil? (proc/read-line! c))
        (if-let [code (proc/await-exit! c)]
          (peer-died code)          ; it exited, and `code` says how
          (still-quiet c)))         ; still alive after the deadline

  nil here is a real answer — \"not within `ms`\" — where a nil from
  `exit-code` only ever meant \"not this instant\". Use `exit-code` directly
  when you are driving your own loop and want the snapshot."
  ([child] (await-exit! child settle-ms))
  ([child ms] (await-thunk (fn [] ((:exit-code child))) ms)))

(defn await-stderr
  "Wait up to `ms` (default 1000) for the child's stderr to produce anything,
  and return the lines — or [] if the deadline passes with none.

  Use this when collecting a crash report: the drain is on a background thread,
  so reading `stderr-lines` the instant a child dies routinely returns [] for a
  child that wrote plenty. That is the failure this exists to remove."
  ([child] (await-stderr child settle-ms))
  ([child ms]
   (or (await-thunk (fn [] (let [e (stderr-lines child)] (when (seq e) e))) ms)
       [])))

;; ------------------------------------------------------------------ stderr
;;
;; A child's stderr MUST be drained, whether or not anyone reads it. It is not a
;; convenience: an undrained pipe fills its OS buffer (typically 64 KiB) and then
;; the CHILD BLOCKS ON WRITE — forever. The symptom is a peer that completes its
;; handshake and then goes silent, with nothing in any log to explain it, which
;; is indistinguishable from a hung peer and is one of the worst things to debug.
;; Verbose MCP servers hit it. Reported by the toolnexus port, 2026-07-31.
;;
;; So a background reader drains it always, into a BOUNDED ring — a chatty
;; server must not grow the caller's heap — and `stderr-lines` hands back what is
;; there. `future` is the concurrency primitive because it is the one that exists
;; on every host that has `spawn` (JVM and cljgo, verified 2026-07-31).

(def ^:private stderr-keep
  "How many trailing stderr lines a child retains. Enough to explain a crash,
  small enough that a server logging in a loop cannot exhaust memory."
  200)

(defn- ring-conj
  "Append to a bounded vector, dropping from the front. Pure."
  [v line]
  (let [v (conj (or v []) line)]
    (if (> (count v) stderr-keep)
      (subvec v (- (count v) stderr-keep))
      v)))

(defn- drain-into!
  "Read `read-line-fn` to EOF on a background thread, appending each line to the
  `sink` atom's ring. Returns nil immediately. Never throws into the caller: the
  child dying mid-read is the normal way this ends."
  [sink read-line-fn]
  (run-async!
   (fn []
     (loop []
       (when-let [line (read-line-fn)]
         (swap! sink ring-conj line)
         (recur)))))
  nil)

(defn spawn
  "Start `command` (a vector) as a LONG-LIVED child with piped stdin/stdout and
  return a child handle — a map of closures, see above. This is what a
  line-delimited JSON-RPC transport (MCP stdio) requires; `sh` cannot express it.

  The child's STDERR is drained from the moment it starts — see the comment
  above; not draining it deadlocks the child once the pipe buffer fills. Read it
  with `stderr-lines`.

  opts: :dir :env"
  ([command] (spawn command {}))
  ([command {:keys [dir env]}]
   #?(:clj
      (let [pb  (ProcessBuilder. ^java.util.List (vec (map str command)))
            _   (when dir (.directory pb (java.io.File. ^String dir)))
            _   (when env (let [m (.environment pb)]
                            (doseq [[k v] env] (.put m (str k) (str v)))))
            p   (.start pb)
            out (java.io.OutputStreamWriter. (.getOutputStream p) "UTF-8")
            in  (java.io.BufferedReader.
                  (java.io.InputStreamReader. (.getInputStream p) "UTF-8"))
            err (java.io.BufferedReader.
                  (java.io.InputStreamReader. (.getErrorStream p) "UTF-8"))
            sink (atom [])]
        (drain-into! sink (fn [] (.readLine err)))
        {:send-line!   (fn [s] (.write out (str s "\n")) (.flush out) nil)
         :read-line!   (fn [] (.readLine in))
         :alive?       (fn [] (.isAlive p))
         ;; `.exitValue` THROWS while the process runs, so it is guarded rather
         ;; than caught — a catch here would need a host-specific class name,
         ;; which is the thing this file exists to avoid.
         :exit-code    (fn [] (when-not (.isAlive p) (.exitValue p)))
         :stderr-lines (fn [] @sink)
         :kill!        (fn [] (.destroyForcibly p) (.waitFor p) nil)
         :close!       (fn [] (.close out) (.waitFor p))})

      :cljgo
      ;; cljg.process/spawn hands back live cljg.stream handles — {:in :out :err
      ;; :wait :kill}. Deliberately NOT `require-go '[os/exec]`: raw interop only
      ;; links AOT, and a host-returned value there rides cljgo's
      ;; nil-substituting build pass, where (.StdinPipe cmd) dies at BUILD time.
      (let [p      (cljg.process/spawn (vec (map str command))
                                       (cond-> {}
                                         dir (assoc :dir dir)
                                         env (assoc :env env)))
            exited (atom nil)
            exit-p (promise)
            sink   (atom [])]
        (drain-into! sink (fn [] (cljg.stream/read-line (:err p))))
        ;; A REAPER, and it is not an optimisation — it is what makes `alive?`
        ;; mean the same thing on both hosts.
        ;;
        ;; `exited` used to be set only inside `close!`/`kill!`, so a child that
        ;; exited BY ITSELF still answered `alive? true` here while the JVM
        ;; answered false. One public fn, two answers, in the library whose
        ;; whole job is preventing exactly that. Reported by the toolnexus port
        ;; and verified 2026-07-31: `sh -c 'printf bye; exit 0'` read to EOF,
        ;; then jvm=false, cljgo=true.
        ;;
        ;; This thread is the ONLY caller of (:wait p) — Go's cmd.Wait must not
        ;; be called twice — so close!/kill! read the promise instead of waiting
        ;; themselves.
        (run-async! (fn [] (let [code ((:wait p))]
                             (reset! exited code)
                             (deliver exit-p code))))
        {:send-line!   (fn [s] (cljg.stream/write-line (:in p) (str s)) nil)
         :read-line!   (fn [] (cljg.stream/read-line (:out p)))
         :alive?       (fn [] (nil? @exited))
         ;; The reaper's atom, not cljgo's native :exit-code. cljgo v0.8.5 ships
         ;; both :alive? and :exit-code, and koine should normally consume the
         ;; upstream fix rather than keep its own — but the reaper is not a
         ;; workaround for a bug here, it is what already makes `alive?` honest,
         ;; and it holds the single permitted call to (:wait p). Reading the same
         ;; atom keeps ONE source of truth for "has it exited"; switching to the
         ;; native pair would mean two, which is how they drift.
         :exit-code    (fn [] @exited)
         :stderr-lines (fn [] @sink)
         :kill!        (fn [] ((:kill p)) @exit-p nil)
         :close!       (fn [] (cljg.stream/close (:in p)) @exit-p)})


      :default
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
